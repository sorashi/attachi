package io.github.sorashi.attachi.attachi

import com.intellij.execution.process.ProcessInfo
import com.intellij.execution.process.impl.ProcessListUtil
import com.intellij.icons.ExpUiIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.ui.TableSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.xdebugger.attach.LocalAttachHost
import com.intellij.xdebugger.attach.XAttachDebugger
import com.intellij.xdebugger.attach.XAttachDebuggerProvider
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.table.AbstractTableModel

enum class DebuggerFilter {
    All,
    DotNet,
    LLDB;

    override fun toString(): String {
        return when (this) {
            All -> "All"
            DotNet -> ".NET"
            LLDB -> "LLDB"
        }
    }
}

class ProcessTableModel : AbstractTableModel() {
    private val processes = mutableListOf<ProcessAndDebuggers>()
    override fun getRowCount(): Int = processes.size

    fun addRow(process: ProcessAndDebuggers) {
        processes.add(process)
        fireTableRowsInserted(processes.size - 1, processes.size - 1)
    }

    operator fun get(i: Int): ProcessAndDebuggers {
        return processes[i]
    }

    fun clear() {
        val size = processes.size
        processes.clear()
        if (size > 0) {
            fireTableRowsDeleted(0, size - 1)
        }
    }

    override fun getColumnCount(): Int {
        return 3
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val p = processes[rowIndex]
        return when (columnIndex) {
            0 -> p.process.executableDisplayName
            1 -> p.process.pid
            2 -> p.process.args
            else -> p
        }
    }

    override fun getColumnName(column: Int): String {
        return when (column) {
            0 -> "Name"
            1 -> "PID"
            2 -> "Arguments"
            else -> throw Exception()
        }
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
}

data class ProcessAndDebuggers(val process: ProcessInfo, val debuggers: List<XAttachDebugger>) {
    companion object {
        fun get(project: Project): MutableList<ProcessAndDebuggers> {
            val attachHost = LocalAttachHost.INSTANCE
            val dataHolder = UserDataHolderBase()
            val plist = ProcessListUtil.getProcessList().map { p ->
                val debuggers =
                    XAttachDebuggerProvider.EP.extensionList.filter { it.isAttachHostApplicable(attachHost) }
                        .flatMap { it.getAvailableDebuggers(project, attachHost, p, dataHolder) }
                ProcessAndDebuggers(p, debuggers)
            }.toMutableList()
            return plist
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this.javaClass != other.javaClass) return false
        return this.process == other
    }

    override fun hashCode(): Int {
        return process.hashCode()
    }
}

class ProcessSelectionDialog(private val project: Project) : DialogWrapper(true) {
    private val tableModel: ProcessTableModel = ProcessTableModel()
    private val table: JBTable
    private val combo: ComboBox<DebuggerFilter>

    init {
        title = "Attach Multiple"
        combo = ComboBox<DebuggerFilter>()
        combo.addItem(DebuggerFilter.All)
        combo.addItem(DebuggerFilter.DotNet)
        combo.addItem(DebuggerFilter.LLDB)
        combo.selectedItem = DebuggerFilter.DotNet
        combo.addItemListener { _ -> repopulateTable() }
        table = JBTable(tableModel)
        // disable table editing
        table.setDefaultEditor(Object::class.java, null)
        table.selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        table.autoCreateRowSorter = true
        table.rowSorter.sortKeys = listOf(RowSorter.SortKey(0, SortOrder.ASCENDING))
        TableSpeedSearch.installOn(table) // optionally specify a to-string converter { a -> a.toString() }
        setSize(800, 600)
        init()
    }

    private fun repopulateTable() {
        // clear the table
        tableModel.clear()

        val pcs = ProcessAndDebuggers.get(project).filter { pnd ->
            when (combo.selectedItem as DebuggerFilter) {
                DebuggerFilter.All -> pnd.debuggers.isNotEmpty()
                DebuggerFilter.DotNet -> pnd.debuggers.any { it.debuggerDisplayName == ".NET Debugger" }
                DebuggerFilter.LLDB -> pnd.debuggers.any { it.debuggerDisplayName == "LLDB" }
            }
        }
        for (pc in pcs) {
            tableModel.addRow(pc)
        }
    }

    fun getSelectedItems(): List<ProcessAndDebuggers> {
        val list = mutableListOf<ProcessAndDebuggers>()
        for (i in table.selectedRows) {
            val actual = table.convertRowIndexToModel(i)
            list.add(tableModel[actual])
        }
        return list
    }

    override fun createCenterPanel(): JComponent {
        val pnl = JPanel(BorderLayout())

        repopulateTable()

        val scrollPane = JBScrollPane(table)
        pnl.add(scrollPane, BorderLayout.CENTER)
        pnl.add(combo, BorderLayout.NORTH)
        val frame = JPanel()
        frame.layout = FlowLayout()
        val refreshButton = JButton("Refresh", ExpUiIcons.General.Refresh)
        refreshButton.addActionListener { _ -> repopulateTable() }
        frame.add(refreshButton)
        ProjectAttachingSessionManager.addOnCompletionHandler(project) {
            this.isOKActionEnabled = true
        }
        if (ProjectAttachingSessionManager.isActive(project)) {
            this.isOKActionEnabled = false
            frame.add(JBLabel("An attaching session is in progress. Let it end first.", SwingConstants.LEFT))
        }
        pnl.add(frame, BorderLayout.SOUTH)
        return pnl
    }
}