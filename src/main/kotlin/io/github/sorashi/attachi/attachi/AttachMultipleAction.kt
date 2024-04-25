package io.github.sorashi.attachi.attachi

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.attach.LocalAttachHost
import com.jetbrains.rider.debugger.DotNetDebugProcess
import com.jetbrains.rider.debugger.util.processIdOrNull

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
        Thread {
            for (p in selectedItems) {
                var debugger = p.debuggers.firstOrNull { it.debuggerDisplayName == ".NET Debugger" }
                if (debugger == null) {
                    debugger = p.debuggers.firstOrNull { it.debuggerDisplayName == "LLDB" }
                }
                if (debugger == null) {
                    debugger = p.debuggers.first()
                }
                try {
                    debugger.attachDebugSession(project, attachHost, p.process)
                    // active waiting for the debugger to attach, because it ignores other attach commands while attaching to process
                    while (!dbm.debugSessions.any { (it.debugProcess as DotNetDebugProcess).processIdOrNull == p.process.pid }) {
                        Thread.sleep(100)
                    }
                    println("Attached ${debugger.debuggerDisplayName} to process: ${p.process}")
                } catch (e: Exception) {
                    println("Error attaching ${debugger.debuggerDisplayName} to process: ${p.process.executableDisplayName} (${p.process.pid}): ${e.message}")
                }
            }
        }.start()
    }
}