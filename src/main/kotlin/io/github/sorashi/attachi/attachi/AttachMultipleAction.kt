package io.github.sorashi.attachi.attachi

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import com.intellij.xdebugger.attach.LocalAttachHost
import com.jetbrains.rider.debugger.DotNetDebugProcess
import com.jetbrains.rider.debugger.util.processIdOrNull

class CancellationToken {
    private var cancelled = false
    fun isCancellationRequested(): Boolean = cancelled
    fun cancel() {
        cancelled = true
    }
}

class AttachingSession(
    private val cancellationToken: CancellationToken,
    val thread: Thread,
    val onThreadEndedListener: ThreadEndedListener
) {
    fun cancel() = cancellationToken.cancel()
    fun isCancelled(): Boolean = cancellationToken.isCancellationRequested()
    fun isThreadAlive(): Boolean = thread.isAlive
    fun addOnThreadEndedHandler(onThreadEndedHandler: ThreadEndedHandler) =
        onThreadEndedListener.addHandler(onThreadEndedHandler)
}

object ProjectAttachingSessionManager {
    private val sessions = mutableMapOf<Project, AttachingSession>()
    fun cancel(project: Project) {
        println("Cancelling attaching session in ${project.name}")
        sessions[project]?.cancel()
    }

    fun isCancelled(project: Project): Boolean {
        return sessions[project]?.isCancelled() ?: false
    }

    fun isThreadAlive(project: Project): Boolean {
        return sessions[project]?.isThreadAlive() ?: return false
    }

    fun addOnThreadEndedHandler(project: Project, onThreadEndedHandler: ThreadEndedHandler) {
        sessions[project]?.addOnThreadEndedHandler(onThreadEndedHandler)
    }

    fun addAndStart(
        project: Project,
        cancellationToken: CancellationToken,
        thread: Thread,
        onThreadEndedListener: ThreadEndedListener
    ) {
        if (isThreadAlive(project)) {
            throw Exception("The session is still running")
        }
        sessions[project] = AttachingSession(cancellationToken, thread, onThreadEndedListener)
        if (!thread.isAlive) {
            thread.start()
        }
    }
}

class AttachMultipleActionDebuggerListener(private val project: Project) : XDebuggerManagerListener {
    override fun currentSessionChanged(previousSession: XDebugSession?, currentSession: XDebugSession?) {
        if (currentSession == null) {
            // all debuggers detached
            ProjectAttachingSessionManager.cancel(project)
        }
        super.currentSessionChanged(previousSession, currentSession)
    }
}

class ThreadEndedListener {
    private val handlers = mutableListOf<ThreadEndedHandler>()
    fun addHandler(handler: ThreadEndedHandler) {
        handlers.add(handler)
    }

    fun invoke() {
        for (handler in handlers) {
            handler.invoke()
        }
    }
}

typealias ThreadEndedHandler = () -> Unit


class AttachMultipleAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val psd = ProcessSelectionDialog(project)
        if (!psd.showAndGet()) return
        attachToProcesses(psd.getSelectedItems(), project)
    }

    private fun attachToProcesses(selectedItems: List<ProcessAndDebuggers>, project: Project) {
        val attachHost = LocalAttachHost.INSTANCE
        val dbm = XDebuggerManager.getInstance(project)
        val token = CancellationToken()
        val onThreadEndedListener = ThreadEndedListener()
        val thread = Thread {
            for (p in selectedItems) {
                var debugger = p.debuggers.firstOrNull { it.debuggerDisplayName == ".NET Debugger" }
                if (debugger == null) {
                    debugger = p.debuggers.firstOrNull { it.debuggerDisplayName == "LLDB" }
                }
                if (debugger == null) {
                    debugger = p.debuggers.first()
                }
                try {
                    if (token.isCancellationRequested()) {
                        onThreadEndedListener.invoke()
                        return@Thread
                    }
                    debugger.attachDebugSession(project, attachHost, p.process)
                    // active waiting for the debugger to attach, because it ignores other attach commands while attaching to process
                    while (!dbm.debugSessions.any { (it.debugProcess as DotNetDebugProcess).processIdOrNull == p.process.pid } && !token.isCancellationRequested()) {
                        Thread.sleep(100)
                    }
                    println("Attached ${debugger.debuggerDisplayName} to process: ${p.process}")
                } catch (e: Exception) {
                    println("Error attaching ${debugger.debuggerDisplayName} to process: ${p.process.executableDisplayName} (${p.process.pid}): ${e.message}")
                }
            }
            onThreadEndedListener.invoke()
        }
        ProjectAttachingSessionManager.addAndStart(project, token, thread, onThreadEndedListener)
    }
}