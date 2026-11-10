package com.example.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class EntryAdapter(
    private val onCopyId: (Entry) -> Unit,
    private val onCopyPassword: (Entry) -> Unit,
    private val onEdit: (Entry) -> Unit
) : RecyclerView.Adapter<EntryAdapter.VH>() {

    private var items: List<Entry> = emptyList()

    fun submit(list: List<Entry>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tvName)
        val user: TextView = v.findViewById(R.id.tvUser)
        val btnId: Button = v.findViewById(R.id.btnCopyId)
        val btnPw: Button = v.findViewById(R.id.btnCopyPw)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_entry, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        h.name.text = e.name
        h.user.text = e.userId
        h.btnId.setOnClickListener { onCopyId(e) }
        h.btnPw.setOnClickListener { onCopyPassword(e) }
        h.itemView.setOnClickListener { onEdit(e) }
    }

    override fun getItemCount() = items.size
}
