package id.kuru.sussmp;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.NamespacedKey;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class AltarManager implements Listener {

    private static final String ALTAR_PREFIX = "SUS Altar ";
    public static final Map<String, List<Material>> ALTAR_TASKS = new HashMap<>();
    public static final Map<String, List<Integer>> ALTAR_AMOUNTS = new HashMap<>();
    public static final Map<String, String> ALTAR_NAMES = new HashMap<>();

    static {
        setupAltar("t_diamond_altar", List.of(Material.DIAMOND, Material.GOLD_INGOT, Material.IRON_INGOT), List.of(2, 5, 10), "Diamond Altar");
        setupAltar("t_gold_altar", List.of(Material.GOLD_INGOT, Material.IRON_INGOT, Material.COAL), List.of(5, 10, 20), "Gold Altar");
        setupAltar("t_iron_altar", List.of(Material.IRON_INGOT, Material.COAL, Material.LAPIS_LAZULI), List.of(10, 20, 10), "Iron Altar");
        setupAltar("t_coal_altar", List.of(Material.COAL, Material.LAPIS_LAZULI, Material.REDSTONE), List.of(32, 10, 10), "Coal Altar");
        setupAltar("t_lapis_altar", List.of(Material.LAPIS_LAZULI, Material.REDSTONE, Material.QUARTZ), List.of(10, 10, 5), "Lapis Altar");
        setupAltar("t_redstone_altar", List.of(Material.REDSTONE, Material.QUARTZ, Material.OBSIDIAN), List.of(20, 5, 2), "Redstone Altar");
        setupAltar("t_quartz_altar", List.of(Material.QUARTZ, Material.OBSIDIAN, Material.EMERALD), List.of(5, 2, 2), "Quartz Altar");
        setupAltar("t_obsidian_altar", List.of(Material.OBSIDIAN, Material.EMERALD, Material.NETHERITE_SCRAP), List.of(2, 2, 1), "Obsidian Altar");
        setupAltar("t_emerald_altar", List.of(Material.EMERALD, Material.NETHERITE_SCRAP, Material.BONE), List.of(2, 1, 10), "Emerald Altar");
        setupAltar("t_netherite_scrap_altar", List.of(Material.NETHERITE_SCRAP, Material.BONE, Material.ROTTEN_FLESH), List.of(1, 10, 20), "Netherite Altar");
        setupAltar("t_bone_altar", List.of(Material.BONE, Material.ROTTEN_FLESH, Material.ARROW), List.of(10, 20, 10), "Bone Altar");
        setupAltar("t_rotten_flesh_altar", List.of(Material.ROTTEN_FLESH, Material.ARROW, Material.FEATHER), List.of(20, 10, 5), "Flesh Altar");
        setupAltar("t_arrow_altar", List.of(Material.ARROW, Material.FEATHER, Material.DIAMOND), List.of(10, 5, 1), "Arrow Altar");
        setupAltar("t_feather_altar", List.of(Material.FEATHER, Material.DIAMOND, Material.GOLD_INGOT), List.of(5, 1, 5), "Feather Altar");
        setupAltar("t_ender_dragon_altar", List.of(Material.DRAGON_BREATH, Material.NETHER_STAR, Material.END_CRYSTAL), List.of(2, 1, 1), "Dragon Altar");
    }

    private static void setupAltar(String key, List<Material> mats, List<Integer> amts, String name) {
        ALTAR_TASKS.put(key, mats);
        ALTAR_AMOUNTS.put(key, amts);
        ALTAR_NAMES.put(key, name);
    }

    private final SusPlugin plugin;

    AltarManager(SusPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // Rebuild registrasi altar dari ItemFrame yang masih ada di world (survive restart)
        Bukkit.getScheduler().runTaskLater(plugin, this::rebuildRegistry, 20L);
    }

    void rebuildRegistry() {
        int found = 0;
        for (World w : Bukkit.getWorlds()) {
            for (Entity ent : w.getEntities()) {
                if (!(ent instanceof ItemFrame frame)) continue;
                if (frame.getCustomName() == null) continue;
                String nm = frame.getCustomName();
                if (!nm.startsWith("SUS_ALTAR:")) continue;
                String key = nm.substring(10);
                if (!ALTAR_TASKS.containsKey(key)) continue;
                plugin.altarFrames.put(key, frame.getUniqueId());
                found++;
                // Temukan ArmorStand requirement di dekatnya
                for (Entity near : w.getNearbyEntities(frame.getLocation(), 1.5, 2, 1.5)) {
                    if (near instanceof ArmorStand stand
                            && stand.getCustomName() != null
                            && stand.getCustomName().contains("x ")) {
                        plugin.altarReqStands.put(key, stand.getUniqueId());
                    }
                }
            }
        }
        if (found > 0) Bukkit.getLogger().info("[SusSMP] " + found + " altar terdeteksi kembali dari world.");
    }

    void place(Player p, String altarKey) {
        List<Material> items = ALTAR_TASKS.get(altarKey);
        List<Integer> amounts = ALTAR_AMOUNTS.get(altarKey);
        String altarName = ALTAR_NAMES.get(altarKey);

        if (items == null || altarName == null) {
            p.sendMessage(SusPlugin.PREFIX + "Altar tidak dikenal."); return;
        }

        Block target = p.getTargetBlockExact(3);
        if (target == null || target.getType().isAir()) {
            p.sendMessage(SusPlugin.PREFIX + ChatColor.RED + "Lihat blok dalam 3 blok untuk memasang altar.");
            return;
        }
        Location base = target.getLocation().add(0, 1, 0);
        base.getBlock().setType(Material.STRIPPED_WARPED_HYPHAE);

        // Item Frame — penanda altar (custom name = key, tidak ter-render di world,
        // dipakai untuk identifikasi altar walau server restart)
        ItemFrame frame = base.getWorld().spawn(base.clone().add(0.5, 1.0, 0.5), ItemFrame.class);
        frame.setItem(new ItemStack(items.get(0))); 
        frame.setFixed(true);
        frame.setPersistent(true);
        frame.setCustomName("SUS_ALTAR:" + altarKey);
        plugin.altarFrames.put(altarKey, frame.getUniqueId());

        // ArmorStand display: nama altar — NON-marker supaya punya hitbox (bisa diklik untuk submit)
        ArmorStand nameStand = base.getWorld().spawn(base.clone().add(0.5, 0.7, 0.5), ArmorStand.class);
        nameStand.setInvisible(true);
        String cleanName = altarName.endsWith(" Altar") ? altarName.substring(0, altarName.length() - 6) : altarName;
        nameStand.setCustomName(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "ALTAR " + cleanName.toUpperCase());
        nameStand.setCustomNameVisible(true);
        nameStand.setGravity(false);
        nameStand.setMarker(false);
        nameStand.setSmall(false);
        nameStand.setAI(false);
        nameStand.setCollidable(false);
        nameStand.setPersistent(true);

        // ArmorStand display: requirement items (berubah warna abu-abu/hijau) — NON-marker juga
        ArmorStand reqStand = base.getWorld().spawn(base.clone().add(0.5, 0.4, 0.5), ArmorStand.class);
        reqStand.setInvisible(true);
        reqStand.setCustomNameVisible(true);
        reqStand.setGravity(false);
        reqStand.setMarker(false);
        reqStand.setSmall(false);
        reqStand.setAI(false);
        reqStand.setCollidable(false);
        reqStand.setPersistent(true);

        // Simpan UUID reqStand di ALTAR_NAMES... wait, kita pakai mapping baru
        plugin.altarReqStands.put(altarKey, reqStand.getUniqueId());
        updateAltarDisplay(altarKey);

        p.sendMessage(SusPlugin.PREFIX + ChatColor.GREEN + "Altar " + altarName + " dipasang.");
    }

    void updateAltarDisplay(String altarKey) {
        UUID reqUuid = plugin.altarReqStands.get(altarKey);
        if (reqUuid == null) return;
        
        Entity ent = Bukkit.getEntity(reqUuid);
        if (!(ent instanceof ArmorStand stand)) return;

        List<Material> items = ALTAR_TASKS.get(altarKey);
        List<Integer> amounts = ALTAR_AMOUNTS.get(altarKey);
        if (items == null) return;
        
        boolean isDone = plugin.phase == SusPlugin.Phase.PLAYING
                && !plugin.tasksLeft.contains(new NamespacedKey(plugin, altarKey));
        ChatColor color = isDone ? ChatColor.GREEN : ChatColor.DARK_GRAY;

        StringBuilder requirement = new StringBuilder();
        for(int i=0; i<items.size(); i++){
            requirement.append(amounts.get(i)).append("x ").append(items.get(i).name().replace("_", " ")).append(i < items.size()-1 ? ", " : "");
        }
        stand.setCustomName(color + "" + requirement.toString());
    }

    void refillAll() {
        for (Map.Entry<String, UUID> e : plugin.altarFrames.entrySet()) {
            Entity ent = Bukkit.getEntity(e.getValue());
            List<Material> mats = ALTAR_TASKS.get(e.getKey());
            if (ent instanceof ItemFrame frame && mats != null && !mats.isEmpty()) frame.setItem(new ItemStack(mats.get(0)));
            updateAltarDisplay(e.getKey());
        }
    }

    // Perintah admin untuk menghapus altar spesifik
    void removeAltar(Player p, String altarKey) {
        if (!plugin.altarFrames.containsKey(altarKey)) {
            p.sendMessage(SusPlugin.PREFIX + ChatColor.RED + "Altar '" + altarKey + "' tidak ditemukan.");
            return;
        }

        Entity frame = Bukkit.getEntity(plugin.altarFrames.get(altarKey));
        if (frame != null) {
            // Hapus blok altar di bawah frame
            Location blokLoc = frame.getLocation().subtract(0.5, 1.0, 0.5);
            if (blokLoc.getBlock().getType() == Material.STRIPPED_WARPED_HYPHAE) {
                blokLoc.getBlock().setType(Material.AIR);
            }
            frame.remove();
        }
        plugin.altarFrames.remove(altarKey);

        // Hapus ArmorStand nama dan requirement
        UUID reqUuid = plugin.altarReqStands.remove(altarKey);
        if (reqUuid != null) {
            Entity e = Bukkit.getEntity(reqUuid);
            if (e != null) e.remove();
        }
        // Hapus ArmorStand nama di sekitar
        for (Entity ent : p.getWorld().getNearbyEntities(p.getLocation(), 5, 5, 5)) {
            if (ent instanceof ArmorStand stand && stand.getCustomName() != null) {
                String nm = ChatColor.stripColor(stand.getCustomName());
                if (nm.contains(altarKey) || nm.startsWith("ALTAR")) {
                    ent.remove();
                }
            }
        }
        p.sendMessage(SusPlugin.PREFIX + ChatColor.GREEN + "Altar " + altarKey + " telah dihapus.");
    }

    @EventHandler
    public void onLeftClick(EntityDamageByEntityEvent event) {
        // Klik kiri ItemFrame ATAU ArmorStand hologram = submit task
        String altarKey = null;
        if (event.getEntity() instanceof ItemFrame frame && frame.getCustomName() != null
                && frame.getCustomName().startsWith("SUS_ALTAR:")) {
            altarKey = frame.getCustomName().substring(10);
        } else if (event.getEntity() instanceof ArmorStand stand && stand.getCustomName() != null) {
            // Klik hologram (ArmorStand) → cari frame altar terdekat untuk tahu key-nya
            for (Entity near : stand.getWorld().getNearbyEntities(stand.getLocation(), 2, 2, 2)) {
                if (near instanceof ItemFrame fr && fr.getCustomName() != null
                        && fr.getCustomName().startsWith("SUS_ALTAR:")) {
                    altarKey = fr.getCustomName().substring(10);
                    break;
                }
            }
        }
        if (altarKey == null) return;
        event.setCancelled(true);
        if (!(event.getDamager() instanceof Player p)) return;
        plugin.getLogger().info("[SusSMP] klik altar '" + altarKey + "' oleh " + p.getName()
                + " (phase=" + plugin.phase + ")");
        submitTask(p, altarKey);
    }

    private void submitTask(Player p, String altarKey) {
        List<Material> requiredItems = ALTAR_TASKS.get(altarKey);
        List<Integer> requiredAmounts = ALTAR_AMOUNTS.get(altarKey);
        String altarName = ALTAR_NAMES.get(altarKey);

        if (requiredItems == null || altarName == null) return;

        if (plugin.phase != SusPlugin.Phase.PLAYING) {
            p.sendMessage(SusPlugin.PREFIX + ChatColor.GRAY + "Game belum jalan — altar tidak aktif.");
            return;
        }

        if (!plugin.tasksLeft.contains(new NamespacedKey(plugin, altarKey))) {
             p.sendMessage(SusPlugin.PREFIX + ChatColor.GRAY + "Task altar " + altarName + " sudah selesai!");
             return;
        }

        if (altarKey.equals("t_ender_dragon_altar") && plugin.tasksLeft.size() > 1) {
            p.sendMessage(SusPlugin.PREFIX + ChatColor.RED + "Altar Ender Dragon terkunci! Selesaikan 14 task lainnya.");
            return;
        }

        for(int i=0; i<requiredItems.size(); i++){
            if(countItems(p, requiredItems.get(i)) < requiredAmounts.get(i)){
                p.sendMessage(SusPlugin.PREFIX + ChatColor.RED + "Butuh " + requiredAmounts.get(i)
                    + " " + requiredItems.get(i).name().replace("_", " ") + ".");
                return;
            }
        }

        for(int i=0; i<requiredItems.size(); i++){
            removeItems(p, requiredItems.get(i), requiredAmounts.get(i));
        }

        if (altarKey.equals("t_ender_dragon_altar")) {
            p.getWorld().spawn(p.getLocation().add(0, 5, 0), org.bukkit.entity.EnderDragon.class);
            plugin.summonedBossesByTask.put(altarKey, UUID.randomUUID());
            plugin.broadcast(SusPlugin.PREFIX + ChatColor.GOLD + p.getName() + ChatColor.GRAY
                + " telah mengorbankan " + requiredAmounts.get(0) + " " + requiredItems.get(0).name().replace("_", " ") + " — Ender Dragon bangkit!");
            plugin.tasksLeft.remove(new NamespacedKey(plugin, altarKey));
            plugin.tasksDone++;
            plugin.updateBar();
            updateAltarDisplay(altarKey);
            if (plugin.tasksLeft.isEmpty()) {
                plugin.crewWin();
            }
        } else {
            p.getInventory().addItem(new ItemStack(Material.ENDER_EYE));
            plugin.tasksLeft.remove(new NamespacedKey(plugin, altarKey));
            plugin.broadcast(SusPlugin.PREFIX + ChatColor.GOLD + p.getName() + ChatColor.GRAY
                + " menyelesaikan task " + altarName + "! Mendapatkan 1x Ender Eye.");
            plugin.tasksDone++;
            plugin.updateBar();
            updateAltarDisplay(altarKey);
            if (plugin.tasksLeft.isEmpty()) plugin.crewWin();
        }

        Entity fe = Bukkit.getEntity(plugin.altarFrames.get(altarKey));
        if (fe instanceof ItemFrame f) f.setItem(null);
    }

    private static int countItems(Player p, Material mat) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && it.getType() == mat) n += it.getAmount();
        }
        return n;
    }

    private static void removeItems(Player p, Material mat, int amount) {
        int remaining = amount;
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (it == null || it.getType() != mat) continue;
            int amt = it.getAmount();
            if (amt <= remaining) {
                p.getInventory().setItem(i, null);
                remaining -= amt;
            } else {
                it.setAmount(amt - remaining);
                p.getInventory().setItem(i, it);
                return;
            }
            if (remaining <= 0) return;
        }
    }

    private boolean isNearAltar(Location loc) {
        return loc.getBlock().getType() == Material.STRIPPED_WARPED_HYPHAE;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        if (isNearAltar(e.getBlock().getLocation())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(SusPlugin.PREFIX + ChatColor.YELLOW + "Altar ini tidak bisa dihancurkan!");
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        if (isNearAltar(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler
    public void onExplosion(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> b.getType() == Material.STRIPPED_WARPED_HYPHAE);
    }

    @EventHandler
    public void onHangingBreak(HangingBreakEvent e) {
        if (e.getEntity() instanceof ItemFrame && isNearAltar(e.getEntity().getLocation())) e.setCancelled(true);
    }

    @EventHandler
    public void onFrameInteract(PlayerInteractEntityEvent e) {
        if (e.getRightClicked() instanceof ItemFrame && isNearAltar(e.getRightClicked().getLocation())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onFrameDamage(EntityDamageByEntityEvent e) {
        if (e.getEntity() instanceof ItemFrame && isNearAltar(e.getEntity().getLocation())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent e) {
        if (e.getRightClicked().getUniqueId() != null
                && plugin.altarReqStands.containsValue(e.getRightClicked().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onArmorStandDamage(EntityDamageByEntityEvent e) {
        if (e.getEntity() instanceof ArmorStand stand && stand.getCustomName() != null
                && isNearAltar(stand.getLocation())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onArmorStandOtherDamage(org.bukkit.event.entity.EntityDamageEvent e) {
        if (e instanceof EntityDamageByEntityEvent) return;
        if (e.getEntity() instanceof ArmorStand stand && stand.getCustomName() != null
                && isNearAltar(stand.getLocation())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityCombust(org.bukkit.event.entity.EntityCombustEvent e) {
        if (e.getEntity() instanceof ArmorStand stand && stand.getCustomName() != null
                && isNearAltar(stand.getLocation())) {
            e.setCancelled(true);
        }
    }
}
