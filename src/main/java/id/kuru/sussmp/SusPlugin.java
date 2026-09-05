package id.kuru.sussmp;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class SusPlugin extends JavaPlugin implements Listener {

    static final String PREFIX = ChatColor.DARK_AQUA + "[SUS] " + ChatColor.GRAY;

    enum Phase { LOBBY, PLAYING, MEETING, ENDED }

    final Set<UUID> players = new HashSet<>();
    final Map<UUID, Boolean> alive = new HashMap<>();   
    final Map<UUID, Boolean> impostor = new HashMap<>();
    final Map<UUID, Location> preMeeting = new HashMap<>();

    final Map<String, UUID> altarFrames = new HashMap<>();      
    final Map<String, UUID> altarReqStands = new HashMap<>();   
    final Map<String, UUID> summonedBossesByTask = new HashMap<>(); 
    final Set<NamespacedKey> tasksLeft = new HashSet<>(); 
    int tasksDone;

    Phase phase = Phase.LOBBY;
    Location meetingRoom;
    long lastMeeting = -1;          
    int meetingDuration = 60;       
    int meetingCooldown = 300;      
    int impostorCount = 1;
    int compassDuration = 30;       // detik locator aktif
    int compassCooldown = 60;       // detik cooldown compass

    final Map<UUID, Long> compassCooldownUntil = new HashMap<>();
    final Map<UUID, Long> locatorExpiry = new HashMap<>();
    final Map<UUID, BossBar> locatorBars = new HashMap<>();

    AltarManager altar;

    BossBar bossbar; 
    MeetingVote vote;               
    VoteGUI voteGui;                
    Random rng = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        meetingDuration = getConfig().getInt("meeting.duration-seconds", 60);
        meetingCooldown = getConfig().getInt("meeting.cooldown-seconds", 300);
        impostorCount  = getConfig().getInt("impostors", 1);
        compassDuration = getConfig().getInt("compass.duration-seconds", 30);
        compassCooldown = getConfig().getInt("compass.cooldown-seconds", 60);

        getServer().getPluginManager().registerEvents(this, this);
        altar = new AltarManager(this);
        voteGui = new VoteGUI(this);
        new ChatVoteListener(this);
        new GameRulesListener(this);
        
        NamespacedKey eyeKey = NamespacedKey.minecraft("ender_eye");
        Bukkit.removeRecipe(eyeKey);
        
        var cmd = getCommand("sus");
        var exec = new SusCommand(this);
        cmd.setExecutor(exec);
        cmd.setTabCompleter(exec);
        var vcmd = getCommand("vote");
        if (vcmd != null) vcmd.setExecutor(new VoteCommand(this));

        getLogger().info("SusSMP aktif — 15 altar item task diimplementasikan.");
    }

    @Override
    public void onDisable() {
        if (bossbar != null) { bossbar.removeAll(); bossbar = null; }
        for (UUID bossId : summonedBossesByTask.values()) {
            Entity e = Bukkit.getEntity(bossId);
            if (e != null) e.remove();
        }
    }

    ItemStack bellItem() {
        ItemStack it = new ItemStack(Material.BELL);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.GOLD + "Emergency Bell");
        m.setLore(List.of(ChatColor.GRAY + "Klik kanan untuk memanggil meeting.",
                ChatColor.GRAY + "Cooldown " + meetingCooldown / 60 + " menit."));
        m.addEnchant(Enchantment.UNBREAKING, 1, true);
        m.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        it.setItemMeta(m);
        return it;
    }

    boolean isBell(ItemStack it) {
        return it != null && it.getType() == Material.BELL
                && it.hasItemMeta() && it.getItemMeta().hasDisplayName()
                && it.getItemMeta().getDisplayName().contains("Emergency Bell");
    }

    /* ---------- compass impostor ---------- */

    ItemStack compassItem() {
        ItemStack it = new ItemStack(Material.COMPASS);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.RED + "Impostor Compass");
        m.setLore(List.of(ChatColor.GRAY + "Klik kanan untuk melihat locator player lain selama "
                        + compassDuration + " detik.",
                ChatColor.GRAY + "Cooldown " + compassCooldown + " detik.",
                ChatColor.DARK_RED + "Tidak bisa dibuang atau dipindahkan keluar inventory."));
        m.addEnchant(Enchantment.UNBREAKING, 1, true);
        m.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        it.setItemMeta(m);
        return it;
    }

    boolean isCompass(ItemStack it) {
        return it != null && it.getType() == Material.COMPASS
                && it.hasItemMeta() && it.getItemMeta().hasDisplayName()
                && it.getItemMeta().getDisplayName().contains("Impostor Compass");
    }

    void activateCompass(Player p) {
        UUID u = p.getUniqueId();
        long now = System.currentTimeMillis();
        long cdUntil = compassCooldownUntil.getOrDefault(u, 0L);
        if (now < cdUntil) {
            p.sendMessage(PREFIX + ChatColor.RED + "Compass cooldown! Tunggu "
                    + (cdUntil - now) / 1000 + " dtk lagi.");
            return;
        }
        compassCooldownUntil.put(u, now + compassCooldown * 1000L);
        locatorExpiry.put(u, now + compassDuration * 1000L);
        p.sendMessage(PREFIX + ChatColor.GREEN + "Locator aktif selama " + compassDuration + " detik.");

        // Bossbar locator milik impostor — hanya impostor yang melihatnya
        BossBar loc = Bukkit.createBossBar(ChatColor.RED + "LOCATOR aktif", BarColor.RED, BarStyle.SOLID);
        loc.addPlayer(p);
        locatorBars.put(u, loc);

        // Task update isi bossbar (daftar nama player terdekat) + auto-expire
        new BukkitRunnable() {
            @Override public void run() {
                Player pl = Bukkit.getPlayer(u);
                if (pl == null || phase != Phase.PLAYING || !players.contains(u)
                        || System.currentTimeMillis() >= locatorExpiry.getOrDefault(u, 0L)) {
                    BossBar b = locatorBars.remove(u);
                    if (b != null) b.removeAll();
                    if (pl != null && phase == Phase.PLAYING && players.contains(u))
                        pl.sendMessage(PREFIX + ChatColor.GRAY + "Locator mati.");
                    cancel();
                    return;
                }
                StringBuilder sb = new StringBuilder();
                for (UUID v : players) {
                    if (v.equals(u) || !alive.getOrDefault(v, false)) continue;
                    Player t = Bukkit.getPlayer(v);
                    if (t == null || !t.getWorld().equals(p.getWorld())) continue;
                    int dist = (int) t.getLocation().distance(p.getLocation());
                    sb.append(t.getName()).append(" (").append(dist).append("m)  ");
                }
                long left = (locatorExpiry.getOrDefault(u, 0L) - System.currentTimeMillis()) / 1000;
                loc.setTitle(ChatColor.RED + "LOCATOR " + left + "s: "
                        + (sb.length() == 0 ? "tidak ada player di world ini" : sb.toString()));
                loc.setProgress(Math.max(0.0, Math.min(1.0, left / (double) compassDuration)));
            }
        }.runTaskTimer(this, 0L, 10L);
    }

    void startGame() {
        List<UUID> list = new ArrayList<>(players);
        if (list.size() < 2) { broadcast(PREFIX + "Minimal 2 pemain."); return; }
        for (UUID u : players) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            p.getInventory().clear();
            p.setGameMode(GameMode.SURVIVAL);
            p.setHealth(20.0);
            p.setFoodLevel(20);
        }
        phase = Phase.LOBBY;
        broadcast(PREFIX + "Game dimulai dalam…");
        new BukkitRunnable() {
            int n = 3;
            @Override public void run() {
                if (n > 0) {
                    for (UUID u : players) {
                        Player p = Bukkit.getPlayer(u);
                        if (p != null) p.sendTitle(ChatColor.YELLOW + "" + ChatColor.BOLD + n,
                                ChatColor.GRAY + "Bersiap…", 2, 18, 2);
                    }
                    n--;
                    return;
                }
                cancel();
                revealRolesAndBegin();
            }
        }.runTaskTimer(this, 1L, 20L);
    }

    private void revealRolesAndBegin() {
        List<UUID> list = new ArrayList<>(players);
        impostorCount = Math.max(1, Math.min(impostorCount, list.size() - 1));
        for (UUID u : players) { alive.put(u, true); impostor.put(u, false); }
        List<UUID> imps = new ArrayList<>();
        for (int i = 0; i < impostorCount; i++) {
            UUID pick = list.remove(rng.nextInt(list.size()));
            impostor.put(pick, true);
            imps.add(pick);
        }
        rollThenReveal(list, imps);
    }

    private void rollThenReveal(List<UUID> innocents, List<UUID> imps) {
        List<String> pool = List.of("DETECTIVE", "MEDIC", "ENGINEER", "SNIPER",
                "SPY", "JESTER", "VETERAN", "IMPOSTOR", "INNOCENT");
        long at = 4L;
        for (int k = 0; k < 15; k++) {
            final String roll = pool.get(rng.nextInt(pool.size()));
            final int idx = k;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                for (UUID u : players) {
                    Player p = Bukkit.getPlayer(u);
                    if (p == null) continue;
                    p.sendTitle(ChatColor.YELLOW.toString() + ChatColor.BOLD + roll,
                            ChatColor.GRAY + "Rolling role…", 0, 25, 0);
                    p.playSound(p, Sound.UI_BUTTON_CLICK, 0.5f, 0.75f + idx * 0.04f);
                }
            }, at);
            at += (k < 10) ? 2L : 4L + (k - 10) * 2L;
        }
        Bukkit.getScheduler().runTaskLater(this, () -> revealImpostors(innocents, imps), at);
    }

    private void revealImpostors(List<UUID> list, List<UUID> imps) {
        final int[] step = {0};
        new BukkitRunnable() {
            @Override public void run() {
                if (step[0] < imps.size()) {
                    Player p = Bukkit.getPlayer(imps.get(step[0]++));
                    if (p != null) {
                        p.sendTitle(ChatColor.RED + "IMPOSTOR", ChatColor.GRAY + "Bunuh semua tanpa ketahuan!", 10, 60, 10);
                        p.playSound(p, Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 0.6f);
                    }
                    return;
                }
                cancel();
                beginPlay(list, imps);
            }
        }.runTaskTimer(this, 30L, 30L);
    }

    private void beginPlay(List<UUID> innocents, List<UUID> imps) {
        for (UUID u : innocents) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && !imps.contains(u)) {
                p.sendTitle(ChatColor.GREEN + "INNOCENT", ChatColor.AQUA + "Selesaikan 15 task altar!", 10, 60, 10);
                p.playSound(p, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
            }
        }
        tasksLeft.clear(); tasksDone = 0;
        AltarManager.ALTAR_NAMES.keySet().forEach(k -> tasksLeft.add(new NamespacedKey(this, k)));
        summonedBossesByTask.clear();
        altar.refillAll();
        for (UUID u : players) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            p.setGameMode(GameMode.SURVIVAL);
            p.getInventory().addItem(bellItem());
            if (impostor.getOrDefault(u, false)) {
                p.getInventory().addItem(compassItem());
            }
        }
        bossbar = Bukkit.createBossBar(barTitle(), BarColor.YELLOW, BarStyle.SEGMENTED_10);
        players.forEach(u -> {
            Player p = Bukkit.getPlayer(u);
            if (p != null && impostor.getOrDefault(u, false)) bossbar.addPlayer(p);
        });
        updateBar();
        phase = Phase.PLAYING;
        broadcast(PREFIX + ChatColor.YELLOW + "Game dimulai! Cari 15 altar dan kumpulkan Ender Eye.");
    }

    String barTitle() {
        return "Task altar: " + tasksDone + "/" + AltarManager.ALTAR_NAMES.size();
    }

    void updateBar() {
        if (bossbar == null) return;
        bossbar.setTitle(barTitle());
        bossbar.setProgress((double) tasksDone / AltarManager.ALTAR_NAMES.size());
    }

    void bossKilled(NamespacedKey key) {
        if (!key.getKey().equals("t_ender_dragon_altar")) return;
        summonedBossesByTask.remove(key.getKey());
        crewWin();
    }

    void crewWin() {
        phase = Phase.ENDED;
        if (bossbar != null) bossbar.removeAll();
        broadcastTitle(ChatColor.GREEN + "" + ChatColor.BOLD + "CREW WIN", ChatColor.WHITE + "Semua tugas selesai!");
        broadcast(PREFIX + ChatColor.GREEN + "" + ChatColor.BOLD + "CREW WIN!");
        scheduleReset();
    }

    void traitorWin() {
        phase = Phase.ENDED;
        if (bossbar != null) bossbar.removeAll();
        broadcastTitle(ChatColor.RED + "" + ChatColor.BOLD + "TRAITOR WIN",
                ChatColor.GRAY + "Impostor membantai semuanya…");
        broadcast(PREFIX + ChatColor.RED + "" + ChatColor.BOLD + "TRAITOR WIN!");
        scheduleReset();
    }

    void broadcastTitle(String title, String subtitle) {
        for (UUID u : players) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.sendTitle(title, subtitle, 15, 60, 15);
        }
    }

    void scheduleReset() {
        new BukkitRunnable() {
            @Override public void run() { reset(); }
        }.runTaskLater(this, 200L);
    }

    boolean castVote(MeetingVote vote, Player voter, UUID target) {
        UUID vu = voter.getUniqueId();
        if (!vote.eligible.contains(vu)) return false;
        vote.votes.put(vu, target);
        voter.sendMessage(PREFIX + "Vote tercatat: "
                + (target == null ? "SKIP" : Bukkit.getOfflinePlayer(target).getName()));
        if (vote.votes.keySet().containsAll(vote.eligible)) vote.finish();
        return true;
    }

    void reset() {
        phase = Phase.LOBBY;
        if (bossbar != null) { bossbar.removeAll(); bossbar = null; }
        for (UUID bossId : summonedBossesByTask.values()) {
            Entity e = Bukkit.getEntity(bossId);
            if (e != null) e.remove();
        }
        summonedBossesByTask.clear();
        for (UUID u : players) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            p.setGameMode(GameMode.SURVIVAL);
            p.getInventory().clear();
            p.getInventory().setArmorContents(null);
            for (PotionEffect pe : p.getActivePotionEffects()) p.removePotionEffect(pe.getType());
        }
        alive.clear(); impostor.clear(); preMeeting.clear();
        lastMeeting = -1;
        tasksLeft.clear(); tasksDone = 0;
        vote = null;
        for (BossBar b : locatorBars.values()) b.removeAll();
        locatorBars.clear();
        locatorExpiry.clear();
        compassCooldownUntil.clear();
        broadcast(PREFIX + "Game direset. /sus add + /sus start untuk main lagi.");
    }

    boolean tryOpenMeeting(Player caller, boolean emergency) {
        if (phase != Phase.PLAYING) { caller.sendMessage(PREFIX + "Game belum jalan."); return false; }
        long now = getServer().getWorlds().getFirst().getGameTime();
        if (lastMeeting > 0 && now - lastMeeting < meetingCooldown * 20L) {
            long left = (meetingCooldown - (now - lastMeeting) / 20L);
            caller.sendMessage(PREFIX + "Meeting cooldown! Tunggu " + left + " dtk lagi.");
            return false;
        }
        openMeeting(caller, emergency ? "darurat!" : "menemukan mayat!");
        return true;
    }

    void openMeeting(Player caller, String reason) {
        phase = Phase.MEETING;
        lastMeeting = getServer().getWorlds().getFirst().getGameTime();
        Location room = meetingRoom != null ? meetingRoom : caller.getLocation();
        preMeeting.clear();
        int i = 0;
        List<Player> seated = new ArrayList<>();
        for (UUID u : new ArrayList<>(players)) {
            Player p = Bukkit.getPlayer(u);
            if (p == null || !alive.getOrDefault(u, false)) continue;
            preMeeting.put(u, p.getLocation());
            double ang = Math.PI * 2 * i++ / 8.0;
            Location seat = room.clone().add(Math.cos(ang) * 3, 0, Math.sin(ang) * 3);
            seat.setYaw((float) Math.toDegrees(-ang));
            p.teleport(seat);
            seated.add(p);
            p.sendTitle(ChatColor.GOLD + "" + ChatColor.BOLD + "MEETING",
                    ChatColor.GRAY + reason, 5, 40, 10);
        }
        vote = new MeetingVote(this, caller.getName(), reason);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (Player p : seated) voteGui.open(p);
        }, 5L);
    }

    void endMeeting(UUID ejected) {
        phase = Phase.PLAYING;
        for (Map.Entry<UUID, Location> e : preMeeting.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) p.teleport(e.getValue());
        }
        preMeeting.clear();
        if (ejected == null) {
            broadcast(PREFIX + "Vote skip — tidak ada yang diterusir.");
            vote = null;
            checkWinConditions();
            return;
        }
        Player out = Bukkit.getPlayer(ejected);
        String name = out != null ? out.getName() : "?";
        boolean wasImp = impostor.getOrDefault(ejected, false);
        alive.put(ejected, false);
        broadcast(PREFIX + ChatColor.RED + name + ChatColor.GRAY + " diterusir! Dia "
                + (wasImp ? ChatColor.RED + "IMPOSTOR!" : ChatColor.GREEN + "INNOCENT…"));
        if (out != null) {
            out.setGameMode(GameMode.SPECTATOR);
            out.sendTitle(ChatColor.RED + "DITERUSIR", ChatColor.GRAY + "Dia "
                    + (wasImp ? ChatColor.RED + "IMPOSTOR!" : ChatColor.GREEN + "INNOCENT…"), 10, 60, 10);
            out.playSound(out, Sound.ENTITY_ENDER_DRAGON_FLAP, 1f, 0.8f);
        }
        vote = null;
        checkWinConditions();
    }

    void killPlayer(Player p) {
        UUID u = p.getUniqueId();
        if (!players.contains(u) || !alive.getOrDefault(u, false)) return;
        alive.put(u, false);
        p.setGameMode(GameMode.SPECTATOR);
        checkWinConditions();
    }

    void checkWinConditions() {
        if (phase == Phase.ENDED) return;
        long aliveImp = 0, aliveCrew = 0;
        for (UUID u : players) {
            if (!alive.getOrDefault(u, false)) continue;
            if (impostor.getOrDefault(u, false)) aliveImp++; else aliveCrew++;
        }
        if (aliveImp == 0) { crewWin(); }
        else if (aliveCrew <= aliveImp) { traitorWin(); }
    }

    /* ---------- events ---------- */

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!players.contains(p.getUniqueId())) return;
        // Cek item di main hand dan off hand
        ItemStack main = e.getItem();
        ItemStack off = p.getInventory().getItemInOffHand();
        if (isBell(main) || isBell(off)) {
            e.setCancelled(true);
            tryOpenMeeting(p, true);
            return;
        }
        if (isCompass(main) || isCompass(off)) {
            e.setCancelled(true);
            if (impostor.getOrDefault(p.getUniqueId(), false)) {
                activateCompass(p);
            } else {
                p.sendMessage(PREFIX + ChatColor.RED + "Bukan impostor!");
            }
        }
    }

    @EventHandler
    public void onBellDrop(PlayerDropItemEvent e) {
        if (isBell(e.getItemDrop().getItemStack()) || isCompass(e.getItemDrop().getItemStack())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onBellClick(org.bukkit.event.inventory.InventoryClickEvent e) {
        ItemStack cur = e.getCurrentItem();
        ItemStack cursor = e.getCursor();
        boolean movingCompass = isCompass(cur) || isCompass(cursor);
        boolean movingBell = isBell(cur) || isBell(cursor);
        if (!movingCompass && !movingBell) return;
        // Item khusus boleh dipindah-pindah DI DALAM inventory pemain sendiri,
        // tapi tidak boleh dimasukkan ke chest/container lain.
        var clicked = e.getClickedInventory();
        var bottom = e.getView().getBottomInventory();
        // Geser antar slot inventory sendiri = klik inventory bawah tanpa container lain
        if (clicked == bottom && (e.getView().getTopInventory() == null
                || e.getView().getTopInventory().getType() == org.bukkit.event.inventory.InventoryType.CRAFTING)) {
            return; // izinkan geser di inventory sendiri
        }
        e.setCancelled(true);
    }

    @EventHandler
    public void onBellDrag(org.bukkit.event.inventory.InventoryDragEvent e) {
        for (ItemStack it : e.getNewItems().values()) {
            if (isBell(it) || isCompass(it)) { e.setCancelled(true); return; }
        }
    }

    @EventHandler
    public void onBossKill(EntityDeathEvent e) {
        if (phase != Phase.PLAYING) return;
        if (e.getEntityType() == org.bukkit.entity.EntityType.ENDER_DRAGON) {
            bossKilled(new NamespacedKey(this, "t_ender_dragon_altar")); 
        }
    }
    
    NamespacedKey keyOf(String name) {
        return new NamespacedKey(this, name);
    }

    void broadcast(String msg) { getServer().broadcastMessage(msg); }

    Scoreboard teamBoard() { return getServer().getScoreboardManager().getMainScoreboard(); }
}