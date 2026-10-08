package com.meuplayer.tv

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load

/** Grade de canais: cartão com logo, selo de resolução e nome. */
class ChannelAdapter(
    private val ctx: Context,
    private val resolver: LogoResolver,
    private val onClick: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    private var items: List<Channel> = emptyList()

    fun submit(list: List<Channel>) {
        items = list
        notifyDataSetChanged()
    }

    fun positionOf(url: String): Int = items.indexOfFirst { it.url == url }

    /** A base de logos terminou de carregar: refaz só as imagens, sem mexer na rolagem. */
    fun refreshLogos() {
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size, PAYLOAD_LOGO)
    }

    /** O player aprendeu novas resoluções: atualiza só os selos. */
    fun refreshBadges() {
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size, PAYLOAD_BADGE)
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val glow: ImageView = view.findViewById(R.id.glow)
        val logo: ImageView = view.findViewById(R.id.logo)
        val initials: TextView = view.findViewById(R.id.initials)
        val badge: TextView = view.findViewById(R.id.badge)
        val name: TextView = view.findViewById(R.id.name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        val holder = VH(view)
        FocusFx.attach(view, holder.glow)
        view.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(items[pos])
        }
        return holder
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        FocusFx.reset(holder.itemView, holder.glow)
        holder.name.text = ch.displayName
        holder.initials.text = initialsOf(ch.displayName)
        bindBadge(holder, ch)
        bindLogo(holder, ch)
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }
        val ch = items[position]
        if (payloads.contains(PAYLOAD_BADGE)) bindBadge(holder, ch)
        if (payloads.contains(PAYLOAD_LOGO)) bindLogo(holder, ch)
    }

    private fun bindBadge(holder: VH, ch: Channel) {
        val label = ResolutionStore.badgeFor(ctx, ch)
        if (label == null) {
            holder.badge.visibility = View.GONE
        } else {
            holder.badge.text = label
            holder.badge.visibility = View.VISIBLE
        }
    }

    private fun bindLogo(holder: VH, ch: Channel) {
        val primary = ch.logo.takeIf { it.startsWith("http", ignoreCase = true) }
        val fallback = resolver.find(ch.displayName) ?: resolver.find(ch.name)
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

    private companion object {
        const val PAYLOAD_LOGO = "logo"
        const val PAYLOAD_BADGE = "badge"
    }
}

/** Uma linha do painel de categorias. [key] nulo significa "Todas". */
data class CategoryItem(val key: String?, val label: String, val count: Int)

class CategoryAdapter(
    private val onPick: (String?) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.VH>() {

    private var items: List<CategoryItem> = emptyList()
    private var selected: String? = null

    fun submit(list: List<CategoryItem>, selectedKey: String?) {
        items = list
        selected = selectedKey
        notifyDataSetChanged()
    }

    /** Só muda a marcação; não perde o foco do controle remoto. */
    fun select(key: String?) {
        selected = key
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
    }

    class VH(val view: TextView) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false) as TextView
        val holder = VH(tv)
        tv.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onPick(items[pos].key)
        }
        return holder
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.view.text = "${item.label} (${item.count})"
        holder.view.isSelected = item.key == selected
    }

    override fun getItemCount(): Int = items.size
}
