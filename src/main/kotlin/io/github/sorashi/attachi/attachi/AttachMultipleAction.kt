package io.github.sorashi.attachi.attachi

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.checkCancelled
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportSequentialProgress
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import com.intellij.xdebugger.attach.LocalAttachHost
import com.jetbrains.rider.debugger.DotNetDebugProcess
import com.jetbrains.rider.debugger.util.processIdOrNull
import kotlinx.coroutines.*

object ProjectAttachingSessionManager {
    private val sessions = mutableMapOf<Project, Job>()
    fun cancel(project: Project) {
        sessions[project]?.cancel()
        println("Cancelled attaching session in ${project.name}")
    }

    fun isActive(project: Project): Boolean {
        return sessions[project]?.isActive ?: false
    }

    fun addOnCompletionHandler(project: Project, onThreadEndedHandler: () -> Unit) {
        sessions[project]?.invokeOnCompletion {  onThreadEndedHandler.invoke() }
    }

    fun add(
        project: Project,
        job: Job
    ) {
        if (isActive(project)) {
            throw Exception("The session is still running")
        }
        sessions[project] = job
        if (!job.isActive) {
            job.start()
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

@Service(Service.Level.PROJECT)
class DebuggerAttachingService(private val project: Project, private val coroutineScope: CoroutineScope) {
    fun attach(selectedItems: List<ProcessAndDebuggers>): Job {
       return coroutineScope.launch {
            withBackgroundProgress(project, "Attaching to multiple processes") {
                reportSequentialProgress(selectedItems.size) { reporter ->
                    val dbm = XDebuggerManager.getInstance(project)
                    val attachHost = LocalAttachHost.INSTANCE
                    for (p in selectedItems) {
                        var debugger = p.debuggers.firstOrNull { it.debuggerDisplayName == ".NET Debugger" }
                        if (debugger == null) {
                            debugger = p.debuggers.firstOrNull { it.debuggerDisplayName == "LLDB" }
                        }
                        if (debugger == null) {
                            debugger = p.debuggers.first()
                        }
                        try {
                            reporter.itemStep("Attaching ${p.process.executableDisplayName}")
                            checkCancelled()
                            if (dbm.debugSessions.any { (it.debugProcess as DotNetDebugProcess).processIdOrNull == p.process.pid }) {
                                reporter.itemStep()
                                continue
                            }
                            debugger.attachDebugSession(project, attachHost, p.process)
                            // active waiting for the debugger to attach, because it ignores other attach commands while attaching to process
                            while (!dbm.debugSessions.any { (it.debugProcess as DotNetDebugProcess).processIdOrNull == p.process.pid }) {
                                delay(100)
                                checkCancelled()
                            }
                            checkCancelled()
                            println("Attached ${debugger.debuggerDisplayName} to process: ${p.process}")
                        } catch (e: Exception) {
                            println("Error attaching ${debugger.debuggerDisplayName} to process: ${p.process.executableDisplayName} (${p.process.pid}): ${e.message}")
                        }
                    }
                }
            }
        }
    }
}

class AttachMultipleAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val psd = ProcessSelectionDialog(project)
        if (!psd.showAndGet()) return
        attachToProcesses(psd.getSelectedItems(), project)
    }

    private fun attachToProcesses(selectedItems: List<ProcessAndDebuggers>, project: Project) {
        val service = project.service<DebuggerAttachingService>()
        ProjectAttachingSessionManager.add(project, service.attach(selectedItems))
    }
}