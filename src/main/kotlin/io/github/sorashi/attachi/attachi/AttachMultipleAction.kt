package io.github.sorashi.attachi.attachi

import com.intellij.execution.process.ProcessInfo
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.attach.LocalAttachHost
import com.intellij.xdebugger.attach.XAttachDebuggerProvider
import com.jetbrains.rider.debugger.DotNetDebugProcess
import com.jetbrains.rider.debugger.util.processIdOrNull
import com.jetbrains.rider.run.pid

class AttachMultipleAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val psd = ProcessSelectionDialog(project)
        if (!psd.showAndGet()) return
        attachToProcesses(psd.selectionList.selectedValuesList, project)
    }

    private fun attachToProcesses(selectedItems: List<ProcessInfo>, project: Project) {
        val attachHost = LocalAttachHost.INSTANCE
        val dataHolder = UserDataHolderBase()
        val dbm = XDebuggerManager.getInstance(project)
        Thread {
            for (p in selectedItems) {
                val debuggers =
                    XAttachDebuggerProvider.EP.extensionList.filter { it.isAttachHostApplicable(attachHost) }
                val debuggerList = debuggers.flatMap {
                    it.getAvailableDebuggers(project, attachHost, p, dataHolder)
                }
                for (debugger in debuggerList.filter { it.debuggerDisplayName.contains(".NET Debugger") }) {
                    try {
                        debugger.attachDebugSession(project, attachHost, p)
                        // active waiting for the debugger to attach, because it ignores other attach commands while attaching to process
                        while(!dbm.debugSessions.any { (it.debugProcess as DotNetDebugProcess).processIdOrNull == p.pid }) {
                            Thread.sleep(100)
                        }
                        println("Attached ${debugger.debuggerDisplayName} to process: $p")
                    } catch (e: Exception) {
                        println("Error attaching ${debugger.debuggerDisplayName} to process: ${p.executableDisplayName} (${p.pid}): ${e.message}")
                    }
                }
            }
        }.start()
    }
}