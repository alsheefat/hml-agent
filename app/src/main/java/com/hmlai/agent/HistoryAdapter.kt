package com.hmlai.agent

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class HistoryAdapter(
    private val conversations: MutableList<Conversation>,
    private val onClick: (Conversation) -> Unit,
    private val onOptionsClick: (Conversation, View) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private var activeId: String? = null

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val row: View = view
        val pinIcon: ImageView = view.findViewById(R.id.historyPinIcon)
        val title: TextView = view.findViewById(R.id.historyTitle)
        val moreButton: ImageButton = view.findViewById(R.id.historyMoreButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val conversation = conversations[position]
        holder.title.text = conversation.title
        holder.row.isActivated = conversation.id == activeId
        holder.pinIcon.visibility = if (conversation.pinned) View.VISIBLE else View.GONE
        holder.row.setOnClickListener { onClick(conversation) }
        holder.moreButton.setOnClickListener { onOptionsClick(conversation, holder.moreButton) }
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
