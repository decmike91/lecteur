package fr.decmike.lecteur

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class EntryAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: ((Int) -> Unit)? = null,
) : RecyclerView.Adapter<EntryAdapter.VH>() {

    data class Row(val icon: Int, val title: String, val subtitle: String)

    var rows: List<Row> = emptyList()
        set(v) {
            field = v
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_entry, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = rows[position]
        holder.icon.setImageResource(r.icon)
        holder.title.text = r.title
        holder.subtitle.text = r.subtitle
        holder.subtitle.visibility = if (r.subtitle.isEmpty()) View.GONE else View.VISIBLE
        holder.itemView.setOnClickListener {
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(p)
        }
        holder.itemView.setOnLongClickListener {
            val p = holder.bindingAdapterPosition
            val cb = onLongClick
            if (p != RecyclerView.NO_POSITION && cb != null) {
                cb(p); true
            } else false
        }
    }

    override fun getItemCount() = rows.size
}
