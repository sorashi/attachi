package io.github.sorashi.attachi.attachi

import com.intellij.execution.process.ProcessInfo
import com.intellij.execution.process.impl.ProcessListUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.*

class ProcessSelectionDialog(private val project: Project) : DialogWrapper(true) {
    var selectionList: JBList<ProcessInfo>
    init {
        title = "Attach Multiple"
        selectionList = JBList()
        init()
    }
    override fun createCenterPanel(): JComponent? {
        val pnl = JPanel(BorderLayout())
        val lmodel = DefaultListModel<ProcessInfo>()
        val pcs = ProcessListUtil.getProcessList()
        pcs.sortBy { it.executableName }
        for (pc in pcs) {
            lmodel.addElement(pc);
        }
        ListSpeedSearch.installOn(selectionList)

        selectionList.model = lmodel
        selectionList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        selectionList.isVisible = true
        selectionList.cellRenderer = ProcessInfoCellRenderer()

        val scrollPane = JBScrollPane(selectionList)
        pnl.add(scrollPane, BorderLayout.CENTER)
        return pnl
    }
}

class ProcessInfoCellRenderer  : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        text = (value as ProcessInfo).executableName
        return this
    }
}