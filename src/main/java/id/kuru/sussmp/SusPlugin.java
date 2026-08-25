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
import org.bukkit.entity.EntityType;
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
import org.bukkit.scoreboard.Team;

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
    final Map<UUID, Boolean> alive = new HashMap<>();   // uuid -> hidup?
    final Map<UUID, Boolean> impostor = new HashMap<>();
    final Map<UUID, Location> preMeeting = new HashMap<>();
    final Set<NamespacedKey> tasksLeft = new HashSet<>();
    int tasksDone;

    Phase phase = Phase.LOBBY;
    Location meetingRoom;
    long lastMeeting = -1;          // tick terakhir meeting dibuka
    int meetingDuration = 60;       // detik
    int meetingCooldown = 300;      // detik
    int impostorCount = 1;

    BossBar bossbar;
    MeetingVote vote;               // aktif saat MEETING
    VoteGUI voteGui;                // GUI voting
    Random rng = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        meetingDuration = getConfig().getInt("meeting.duration-seconds", 60);
        meetingCooldown = getConfig().getInt("meeting.cooldown-seconds", 300);
        impostorCount  = getConfig().getInt("impostors", 1);

        getServer().getPluginManager().registerEvents(this, this);
        voteGui = new VoteGUI(this);
        new ChatVoteListener(this);
        new GameRulesListener(this);
        var cmd = getCommand("sus");
        var exec = new SusCommand(this);
        cmd.setExecutor(exec);
        cmd.setTabCompleter(exec);
        var vcmd = getCommand("vote");
        if (vcmd != null) vcmd.setExecutor(new VoteCommand(this));

        getLogger().info("SusSMP aktif — jangan percaya siapa pun.");
    }

    @Override
    public void onDisable() {
        // /reload atau shutdown: pastikan bossbar tidak nyangkut di layar pemain
        if (bossbar != null) { bossbar.removeAll(); bossbar = null; }
    }

    /* ---------- item bell (emergency meeting) ---------- */

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

    /* ---------- alur game ---------- */

    void startGame() {
        List<UUID> list = new ArrayList<>(players);
        if (list.size() < 2) { broadcast(PREFIX + "Minimal 2 pemain."); return; }

        // bersihkan sisa state pemain sebelum mulai
        for (UUID u : players) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            p.getInventory().clear();
            p.setGameMode(GameMode.SURVIVAL);
            p.setHealth(20.0);
            p.setFoodLevel(20);
        }

        // animasi countdown 3-2-1 lalu reveal role
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

        // susun urutan reveal: impostor terakhir biar dramatis
        List<UUID> imps = new ArrayList<>();
        for (int i = 0; i < impostorCount; i++) {
            UUID pick = list.remove(rng.nextInt(list.size()));
            impostor.put(pick, true);
            imps.add(pick);
        }

        rollThenReveal(list, imps);
    }

    /** Animasi rolling ala mesin slot: judul berganti nama role acak, makin lama makin lambat, lalu reveal asli. */
    private void rollThenReveal(List<UUID> innocents, List<UUID> imps) {
        List<String> pool = List.of("DETECTIVE", "MEDIC", "ENGINEER", "SNIPER",
                "SPY", "JESTER", "VETERAN", "IMPOSTOR", "INNOCENT");
        // 10 ganti cepat (@2 tick) lalu 5 makin lambat (jeda 4,6,8,10,12) — total ±3 detik
        long at = 4L;
        for (int k = 0; k < 15; k++) {
            final String roll = pool.get(rng.nextInt(pool.size()));
            final int idx = k;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                for (UUID u : players) {
                    Player p = Bukkit.getPlayer(u);
                    if (p == null) continue;
                    p.sendTitle(ChatColor.YELLOW.toString() + ChatColor.BOLD + roll,
                            ChatColor.GRAY + "Rolling role\u2026", 0, 25, 0);
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
                if (step[0] < imps.size()) {           // tampilkan impostor satu-satu
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
                p.sendTitle(ChatColor.GREEN + "INNOCENT", ChatColor.AQUA + "Selesaikan 4 bos bersama!", 10, 60, 10);
                p.playSound(p, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
            }
        }

        // bos bebas urutan, kecuali Ender Dragon: harus 3 bos lain tumbang dulu
        tasksLeft.clear(); tasksDone = 0;
        tasksLeft.add(new NamespacedKey(this, "t_elder"));
        tasksLeft.add(new NamespacedKey(this, "t_warden"));
        tasksLeft.add(new NamespacedKey(this, "t_wither"));
        tasksLeft.add(new NamespacedKey(this, "t_dragon"));

        // kunci The End: portal frame & ender eye tidak dipakai — blokir via world access di PlayerPortalEvent
        for (UUID u : players) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            p.setGameMode(GameMode.SURVIVAL);
            p.getInventory().addItem(bellItem());
        }

        bossbar = Bukkit.createBossBar(barTitle(), BarColor.YELLOW, BarStyle.SEGMENTED_10);
        bossbar.setProgress(0.0);
        players.forEach(u -> { Player p = Bukkit.getPlayer(u); if (p != null) bossbar.addPlayer(p); });

        phase = Phase.PLAYING;
        broadcast(PREFIX + "Game dimulai! " + ChatColor.YELLOW + "Buru 3 bos dulu — Ender Dragon paling akhir.");
    }

    String bossName(NamespacedKey key) {
        if (key == null) return "";
        return switch (key.getKey()) {
            case "t_elder" -> "Elder Guardian";
            case "t_warden" -> "Warden";
            case "t_wither" -> "Wither";
            default -> "Ender Dragon";
        };
    }

    /** End terbuka hanya setelah 3 bos non-dragon tumbang — urutan bebas. */
    boolean dragonUnlocked() { return tasksDone >= 3; }

    String barTitle() {
        List<String> left = new ArrayList<>();
        for (NamespacedKey k : tasksLeft) if (!k.getKey().equals("t_dragon")) left.add(bossName(k));
        if (left.isEmpty()) return "Bunuh: Ender Dragon";
        return "Bos tersisa: " + String.join(", ", left);
    }

    void updateBar() {
        if (bossbar == null) return;
        bossbar.setTitle(barTitle());
        bossbar.setProgress(tasksDone / 4.0);
    }

    void bossKilled(NamespacedKey key) {
        tasksLeft.remove(key); tasksDone++;
        broadcast(PREFIX + ChatColor.GREEN + bossName(key) + " tumbang! Task " + tasksDone + "/4 selesai.");
        if (tasksLeft.isEmpty()) { crewWin(); return; }
        updateBar();
        if (tasksDone == 3)
            broadcast(PREFIX + "The End " + ChatColor.YELLOW + "terbuka!" + ChatColor.GRAY + " Saatnya Ender Dragon.");
    }

    void crewWin() {
        phase = Phase.ENDED;
        if (bossbar != null) bossbar.removeAll();
        broadcastTitle(ChatColor.GREEN + "" + ChatColor.BOLD + "CREW WIN", ChatColor.WHITE + "Semua task selesai!");
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

    /** Title animasi ke semua pemain game: fade-in, tahan 3 dtk, fade-out. */
    void broadcastTitle(String title, String subtitle) {
        for (UUID u : players) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.sendTitle(title, subtitle, 15, 60, 15);
        }
    }

    void scheduleReset() {
        new BukkitRunnable() {
            @Override public void run() { reset(); }
        }.runTaskLater(this, 200L); // 10 detik
    }

    /** Terima vote (dari chat maupun GUI). Return false jika voter tidak berhak. */
    boolean castVote(MeetingVote vote, Player voter, UUID target) {
        UUID vu = voter.getUniqueId();
        if (!vote.eligible.contains(vu)) return false;
        vote.votes.put(vu, target);
        voter.sendMessage(PREFIX + "Vote tercatat: "
                + (target == null ? "SKIP" : Bukkit.getOfflinePlayer(target).getName()));
        // semua sudah vote? selesai cepat
        if (vote.votes.keySet().containsAll(vote.eligible)) vote.finish();
        return true;
    }

    void reset() {
        phase = Phase.LOBBY;
        if (bossbar != null) { bossbar.removeAll(); bossbar = null; } // fix: bossbar nyangkut saat game selesai/stop
        // pulihkan semua pemain: gamemode + inventory bersih
        for (UUID u : players) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            p.setGameMode(GameMode.SURVIVAL);
            p.getInventory().clear();
            p.getInventory().setArmorContents(null);
            for (PotionEffect pe : p.getActivePotionEffects()) p.removePotionEffect(pe.getType());
        }
        alive.clear(); impostor.clear(); preMeeting.clear();
        lastMeeting = -1; // fix: cooldown bell ikut reset tiap game selesai/stop
        tasksLeft.clear(); tasksDone = 0;
        vote = null;
        broadcast(PREFIX + "Game direset. /sus add + /sus start untuk main lagi.");
    }

    /* ---------- meeting ---------- */

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
        // buka GUI voting setelah teleport selesai (1 tick) biar nggak ketutup paksa
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (Player p : seated) voteGui.open(p);
        }, 5L);
    }

    void endMeeting(UUID ejected) {
        phase = Phase.PLAYING;
        // pulihkan posisi
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
            // ponytail: kepala Steve dihilangkan sesuai permintaan; kalau mau balik,
            // taruh blok PLAYER_HEAD di sini dan catat lokasinya utk dibersihkan saat reset.
        }
        if (!wasImp) {
            // hukum kecil: innocent salah terusir → impostor makin bebas (tanpa efek tambahan)
        }
        vote = null;
        checkWinConditions();
    }

    void killPlayer(Player p) {
        UUID u = p.getUniqueId();
        if (!players.contains(u) || !alive.getOrDefault(u, false)) return;
        alive.put(u, false);
        p.setGameMode(GameMode.SPECTATOR);
        broadcast(PREFIX + ChatColor.RED + p.getName() + ChatColor.GRAY + " telah dibunuh!");
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
        if (!isBell(e.getItem())) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!players.contains(p.getUniqueId())) return;
        tryOpenMeeting(p, true);
    }

    /** Bell tidak bisa dibuang/dipindah/dijatuhkan. */
    @EventHandler
    public void onBellDrop(PlayerDropItemEvent e) {
        if (isBell(e.getItemDrop().getItemStack())) {
            e.setCancelled(true);
        }
    }

    /** Bell tidak bisa dipindah ke chest/hopper/inventaris lain (klik, shift-klik, tukar slot). */
    @EventHandler
    public void onBellClick(org.bukkit.event.inventory.InventoryClickEvent e) {
        if (isBell(e.getCurrentItem()) || isBell(e.getCursor())) e.setCancelled(true);
    }

    @EventHandler
    public void onBellDrag(org.bukkit.event.inventory.InventoryDragEvent e) {
        for (ItemStack it : e.getNewItems().values()) {
            if (isBell(it)) { e.setCancelled(true); return; }
        }
    }

    @EventHandler
    public void onBossKill(EntityDeathEvent e) {
        if (phase != Phase.PLAYING || tasksLeft.isEmpty()) return;
        NamespacedKey hit = switch (e.getEntityType()) {
            case ELDER_GUARDIAN -> keyOf("t_elder");
            case WARDEN         -> keyOf("t_warden");
            case WITHER         -> keyOf("t_wither");
            case ENDER_DRAGON   -> keyOf("t_dragon");
            default -> null;
        };
        if (hit == null || !tasksLeft.contains(hit)) return;
        if (e.getEntity().getKiller() != null
                && players.contains(e.getEntity().getKiller().getUniqueId())) {
            bossKilled(hit);
        }
    }

    private NamespacedKey keyOf(String name) {
        for (NamespacedKey k : tasksLeft) if (k.getKey().equals(name)) return k;
        return null;
    }

    /* ---------- util ---------- */

    void broadcast(String msg) { getServer().broadcastMessage(msg); }

    Scoreboard teamBoard() { return getServer().getScoreboardManager().getMainScoreboard(); }
}
