package com.hmlai.agent

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class HistoryAdapter(
    private val conversations: MutableList<Conversation>,
    private val onClick: (Conversation) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private var activeId: String? = null

    class ViewHolder(val title: TextView) : RecyclerView.ViewHolder(title)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false) as TextView
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val conversation = conversations[position]
        holder.title.text = conversation.title
        holder.title.isActivated = conversation.id == activeId
        holder.title.setOnClickListener { onClick(conversation) }
    }

    override fun getItemCount(): Int = conversations.size

    fun setActive(id: String?) {
        activeId = id
        notifyDataSetChanged()
    }

    fun setConversations(newList: List<Conversation>) {
        conversations.clear()
        conversations.addAll(newList)
        notifyDataSetChanged()
    }
}
