package com.meuplayer.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load

class ChannelAdapter(
    private val resolver: LogoResolver,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    private var items: List<Channel> = emptyList()

    fun submit(list: List<Channel>) {
        items = list
        notifyDataSetChanged()
    }

    /** Chamado quando a base de logos terminou de carregar: refaz só o desenho dos itens. */
    fun refreshLogos() {
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val logo: ImageView = view.findViewById(R.id.logo)
        val initials: TextView = view.findViewById(R.id.initials)
        val name: TextView = view.findViewById(R.id.name)
        val group: TextView = view.findViewById(R.id.group)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        holder.name.text = ch.name
        holder.group.text = GroupNames.display(ch.group)
        holder.initials.text = initialsOf(ch.name)
        bindLogo(holder, ch)
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(pos)
        }
    }

    private fun bindLogo(holder: VH, ch: Channel) {
        val primary = ch.logo.takeIf { it.startsWith("http", ignoreCase = true) }
        val fallback = resolver.find(ch.name)
        val first = primary ?: fallback

        holder.logo.dispose()
        holder.logo.setImageDrawable(null)
        holder.initials.visibility = View.VISIBLE
        if (first == null) return

        holder.logo.load(first) {
            listener(
                onSuccess = { _, _ -> holder.initials.visibility = View.GONE },
                onError = { _, _ ->
                    // O logo da lista falhou: tenta o encontrado pelo nome.
                    if (primary != null && fallback != null && fallback != primary) {
                        holder.logo.load(fallback) {
                            listener(onSuccess = { _, _ -> holder.initials.visibility = View.GONE })
                        }
                    }
                }
            )
        }
    }

    private fun initialsOf(name: String): String {
        val parts = name.trim().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        return when {
            parts.isEmpty() -> "?"
            parts.size == 1 -> parts[0].take(2).uppercase()
            else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
        }
    }

    override fun getItemCount(): Int = items.size
}
