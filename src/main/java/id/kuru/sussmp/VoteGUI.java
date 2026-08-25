package id.kuru.sussmp;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** GUI voting: kepala pemain hidup, klik = vote, slot tengah = skip. */
final class VoteGUI implements Listener {

    static final String TITLE = ChatColor.DARK_AQUA + "Voting — pilih yang dicurigai";
    private final SusPlugin plugin;

    VoteGUI(SusPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /** Buka GUI utk 1 pemain. Dipanggil saat meeting mulai & tiap ada perubahan. */
    void open(Player viewer) {
        List<UUID> aliveList = new ArrayList<>();
        for (UUID u : plugin.players)
            if (plugin.alive.getOrDefault(u, false)) aliveList.add(u);

        int size = Math.min(54, ((aliveList.size() + 1) / 9 + 1) * 9);
        Inventory inv = Bukkit.createInventory(null, size, TITLE);

        for (UUID u : aliveList) {
            Player t = Bukkit.getPlayer(u);
            if (t == null) continue;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta m = (SkullMeta) head.getItemMeta();
            m.setOwnerProfile(t.getPlayerProfile());
            UUID myVote = plugin.vote == null ? null : plugin.vote.votedFor(viewer.getUniqueId());
            m.setDisplayName(u.equals(myVote) ? ChatColor.GREEN + "✔ " + t.getName() : ChatColor.YELLOW + t.getName());
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Vote: " + ChatColor.WHITE
                    + (plugin.vote == null ? 0 : plugin.vote.countVotes(u)));
            lore.add(ChatColor.DARK_GRAY + "Klik untuk vote!");
            m.setLore(lore);
            head.setItemMeta(m);
            inv.addItem(head);
        }

        // skip di slot terakhir
        ItemStack skip = new ItemStack(Material.STRUCTURE_VOID);
        var sm = skip.getItemMeta();
        sm.setDisplayName(ChatColor.GRAY + "SKIP");
        skip.setItemMeta(sm);
        inv.setItem(size - 1, skip);

        viewer.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTitle().equals(TITLE))) return;
        e.setCancelled(true); // GUI read-only
        if (plugin.phase != SusPlugin.Phase.MEETING || plugin.vote == null) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!plugin.players.contains(p.getUniqueId())) return;

        ItemStack it = e.getCurrentItem();
        if (it == null || !it.hasItemMeta() || !it.getItemMeta().hasDisplayName()) return;
        String name = ChatColor.stripColor(it.getItemMeta().getDisplayName());

        if (it.getType() == Material.PLAYER_HEAD) {
            Player target = Bukkit.getPlayerExact(name.replace("✔ ", "").trim());
            if (target == null || !plugin.alive.getOrDefault(target.getUniqueId(), false)) return;
            final UUID tid = target.getUniqueId();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (plugin.vote != null && plugin.castVote(plugin.vote, p, tid)) {
                    p.closeInventory();
                    refreshAll();
                }
            });
        } else if (it.getType() == Material.STRUCTURE_VOID && name.equalsIgnoreCase("SKIP")) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (plugin.vote != null && plugin.castVote(plugin.vote, p, null)) {
                    p.closeInventory();
                    refreshAll();
                }
            });
        }
    }

    /** Refresh semua GUI pemain yang sedang membuka voting (bandingin via title String, bukan Component). */
    void refreshAll() {
        for (UUID u : plugin.players) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            var view = p.getOpenInventory();
            if (view != null && ChatColor.stripColor(view.getTitle()) .startsWith("Voting")) {
                open(p);
            }
        }
    }
}
