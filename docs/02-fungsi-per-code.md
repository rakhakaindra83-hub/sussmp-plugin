# 02 — Fungsi Per Code (Penjelasan Baris Per Bagian)

Dokumentasi teknis: fungsi setiap file dan setiap method di source `SusSMP` (paket `id.kuru.sussmp`).

---

## 1. `SusPlugin.java` (Main Class)

Turunan `JavaPlugin` + `Listener`; titik masuk dan pemilik seluruh state game.

| Anggota | Signature | Fungsi |
|---------|-----------|--------|
| `PREFIX` | `static final String` | `§3[SUS] §7` — awalan semua pesan plugin. |
| `Phase` | `enum` | `LOBBY, PLAYING, MEETING, ENDED`. Satu mesin status; hampir semua handler mengecek ini dulu. |
| `players` / `alive` / `impostor` / `preMeeting` | koleksi UUID | Daftar peserta, status hidup, status impostor, posisi sebelum TP meeting. |
| `tasksLeft` + `tasksDone` | `Set<NamespacedKey>` + int | 4 task bos yang belum tumbang (`t_elder`, `t_warden`, `t_wither`, `t_dragon`) + hitungan selesai. |
| `lastMeeting` | `long` | Tick gameTime meeting terakhir; dasar cooldown bell. `-1` = belum pernah. |
| `onEnable()` | `@Override public void` | Baca config, registrasi listener & command (rincian di `01-cara-pembuatan.md`). |
| `onDisable()` | `@Override public void` | `bossbar.removeAll()` — bossbar tidak nyangkut saat reload/shutdown. |

### Item bell

| Method | Fungsi |
|--------|--------|
| `bellItem()` | Susun `Material.BELL` bernama emas "Emergency Bell", lore petunjuk + cooldown, enchant UNBREAKING dengan `HIDE_ENCHANTS` (kilau tanpa teks enchant). |
| `isBell(ItemStack)` | Deteksi bell milik plugin: tipe BELL **dan** display name mengandung "Emergency Bell". Dipakai semua event proteksi bell. |

### Alur mulai game

```java
void startGame() {                 // validasi >=2 pemain, reset inventory/hp,
    // ... countdown 3-2-1: sendTitle kuning bold angka tiap 20 tick,
    // lalu revealRolesAndBegin();
}
```

- `revealRolesAndBegin()` — clamp `impostorCount` ke `max(1, min(n, size-1))`, set semua `alive=true/impostor=false`, lalu ambil impostor secara acak dari salinan daftar. Impostor dikumpulkan di list terpisah agar **ditampilkan paling akhir**.
- `rollThenReveal(innocents, imps)` — animasi mesin slot:

```java
List<String> pool = List.of("DETECTIVE","MEDIC","ENGINEER","SNIPER",
        "SPY","JESTER","VETERAN","IMPOSTOR","INNOCENT");
// 10 ganti cepat (@2 tick) lalu 5 makin lambat (jeda 4,6,8,10,12) — total ±3 detik
for (int k = 0; k < 15; k++) {
    final String roll = pool.get(rng.nextInt(pool.size()));
    Bukkit.getScheduler().runTaskLater(this, () -> {
        // judul kuning bold nama acak + suara klik pitch naik
        p.sendTitle(ChatColor.YELLOW.toString() + ChatColor.BOLD + roll, "Rolling role…");
        p.playSound(p, Sound.UI_BUTTON_CLICK, 0.5f, 0.75f + idx * 0.04f);
    }, at);
    at += (k < 10) ? 2L : 4L + (k - 10) * 2L;
}
Bukkit.getScheduler().runTaskLater(this, () -> revealImpostors(list, imps), at);
```

- `revealImpostors(list, imps)` — `BukkitRunnable` tiap 30 tick: satu impostor per langkah dapat title merah `IMPOSTOR` ("Bunuh semua tanpa ketahuan!") + suara detak jantung Warden pitch 0.6. Habis → `beginPlay()`.
- `beginPlay(innocents, imps)` — sisa pemain dapat title hijau `INNOCENT`, isi `tasksLeft` dengan 4 key bos, set Survival, bagikan bell, buat bossbar `BarColor.YELLOW / SEGMENTED_10`, set fase `PLAYING`, broadcast "Buru 3 bos dulu — Ender Dragon paling akhir."

### Task bos & bossbar

```java
/** End terbuka hanya setelah 3 bos non-dragon tumbang — urutan bebas. */
boolean dragonUnlocked() { return tasksDone >= 3; }

String barTitle() {
    List<String> left = new ArrayList<>();
    for (NamespacedKey k : tasksLeft) if (!k.getKey().equals("t_dragon")) left.add(bossName(k));
    if (left.isEmpty()) return "Bunuh: Ender Dragon";
    return "Bos tersisa: " + String.join(", ", left);
}

void bossKilled(NamespacedKey key) {
    tasksLeft.remove(key); tasksDone++;
    broadcast(PREFIX + bossName(key) + " tumbang! Task " + tasksDone + "/4 selesai.");
    if (tasksLeft.isEmpty()) { crewWin(); return; }   // dragon mati = menang crew
    updateBar();
    if (tasksDone == 3) broadcast(PREFIX + "The End terbuka! Saatnya Ender Dragon.");
}
```

| Method | Fungsi |
|--------|--------|
| `bossName(key)` | Mapping `t_elder→Elder Guardian`, `t_warden→Warden`, `t_wither→Wither`, sisanya Ender Dragon. |
| `updateBar()` | Set judul `barTitle()` + progres `tasksDone / 4.0` — jadi bar bergerak n/4 total, bukan per bos. |

### Win / reset

| Method | Fungsi |
|--------|--------|
| `crewWin()` / `traitorWin()` | Set fase `ENDED`, hapus bossbar dari semua pemain, title besar CREW WIN (hijau bold) / TRAITOR WIN (merah), panggil `scheduleReset()`. |
| `scheduleReset()` | `reset()` ditunda 200 tick = **10 detik**, memberi waktu membaca layar hasil. |
| `reset()` | Kembali ke LOBBY, `bossbar.removeAll()+null`, pulihkan pemain (Survival, inventory+armor kosong, potion effect dibersihkan), kosongkan map, **`lastMeeting = -1`** (cooldown bell ikut reset), task di-clear, vote dibuang. |
| `checkWinConditions()` | Hitung impostor/crew hidup: impostor habis → `crewWin()`; `aliveCrew <= aliveImp` → `traitorWin()`. Guard `phase == ENDED` mencegah dobel trigger. |
| `killPlayer(p)` | Tandai mati + SPECTATOR + broadcast "telah dibunuh!" + cek win. |

### Meeting

```java
void openMeeting(Player caller, String reason) {
    phase = Phase.MEETING;
    lastMeeting = getServer().getWorlds().getFirst().getGameTime(); // mulai hitung cooldown
    // TP pemain hidup ke kursi melingkar radius 3 blok (maks 8 titik), yaw menghadap pusat;
    // simpan posisi lama di preMeeting; title MEETING;
    vote = new MeetingVote(this, caller.getName(), reason);
    Bukkit.getScheduler().runTaskLater(this, () -> seated.forEach(voteGui::open), 5L); // GUI 5 tick kemudian
}
```

| Method | Fungsi |
|--------|--------|
| `tryOpenMeeting(caller, emergency)` | Gate: harus fase PLAYING + lewat cooldown (`now - lastMeeting < cooldown*20` ditolak dengan sisa detik). Alasan: "darurat!" (klik bell) atau "menemukan mayat!". |
| `endMeeting(ejected)` | Balikin posisi `preMeeting`; ejected null → "Vote skip"; selain itu tandai mati, SPECTATOR, umumkan dia IMPOSTOR/INNOCENT, lalu `checkWinConditions()`. |
| `castVote(vote, voter, target)` | Gerbang tunggal vote chat & GUI: cek `eligible`, catat, konfirmasi "Vote tercatat: NAMA/SKIP", dan **finish lebih awal** kalau semua eligible sudah vote. |

### Event handler di main class

| Handler | Fungsi |
|---------|--------|
| `onInteract` (PlayerInteractEvent) | Klik kanan bell → cancel event + `tryOpenMeeting(emergency=true)`. |
| `onBellDrop` / `onBellClick` / `onBellDrag` | Bell tidak bisa didrop, dipindah slot/chest (current/cursor), atau diseret antar-slot. |
| `onBossKill` (EntityDeathEvent) | Hanya fase PLAYING; mapping EntityType→key bos; syarat pembunuh ada di daftar pemain; lalu `bossKilled(hit)`. Bos yang sudah masuk `tasksLeft` dobel-kill tidak dobel-hitung (cek `contains(hit)`). |

Util kecil: `broadcast(msg)` dan `broadcastTitle(title, sub)` (fade 15/60/15 ke semua peserta).

## 2. `GameRulesListener.java` (Proteksi Aturan)

| Handler | Fungsi |
|---------|--------|
| `onPortal(PlayerPortalEvent)` | Saat PLAYING/MEETING dan cause `END_PORTAL`/`END_GATEWAY`: jika `!plugin.dragonUnlocked()` → cancel + pesan merah "The End masih terkunci! Tumbangkan dulu 3 bos lainnya." Inilah mekanisme kunci The End. |
| `onDeath(PlayerDeathEvent)` | `drops.removeIf(plugin::isBell)` → bell mati bersama pemiliknya, tak nyempil di tanah; `deathMessage(null)` → rahasiaman game; killer impostor → `killPlayer` sync via scheduler; selain itu mati alami → pesan "Kau mati karena sebab alami…" + win check. |
| `onQuit(PlayerQuitEvent)` | Keluar saat PLAYING/MEETING & masih hidup → dinyatakan mati + win check. Keluar saat lobby → sekadar dihapus dari daftar. |

## 3. `ChatVoteListener.java` (Vote via Chat)

Satu method `onChat(AsyncPlayerChatEvent)` dengan `priority = LOWEST, ignoreCancelled = true`:

```java
if (msg.equalsIgnoreCase("!skip")) target = null;          // skip
else if (msg.toLowerCase().startsWith("!vote ")) {
    String name = msg.substring(6).trim();
    Player t = Bukkit.getPlayerExact(name);                 // wajib online, ikut game, masih hidup
}
e.setCancelled(true);                                       // pesan tidak bocor ke chat
Bukkit.getScheduler().runTask(plugin, () -> plugin.vote.castVote(...)); // vote dieksekusi sync
```

Chat biasa (bukan `!vote`/`!skip`) dibiarkan lewat utuh. Target invalid → pesan "Tidak bisa vote X." tanpa mencatat suara. Eksekusi dipindah ke main thread karena event chat async.

## 4. `MeetingVote.java` (Mesin Voting)

| Anggota | Fungsi |
|---------|--------|
| `votes` : `Map<UUID,UUID>` | voter → target; `null` value = skip. Satu orang satu suara (map otomatis overwrite). |
| `eligible` : `List<UUID>` | Pemain yang **masih hidup** saat meeting dibuka — spectator/mati tidak bisa vote. |
| `SKIP = new UUID(0,0)` | Kunci tiruan supaya skip bisa dihitung dalam tally yang sama. |

| Method | Fungsi |
|--------|--------|
| constructor | Isi `eligible` dari pemain hidup; simpan caller + reason untuk pengumuman. |
| `begin(seconds)` | Broadcast EMERGENCY MEETING + instruksi `!vote <nama>` / `!skip` / GUI + suara note pling. Timer per detik: umumkan sisa waktu tiap kelipatan 10 & saat ≤5 (plus title countdown ≤5); habis waktu atau fase berubah → `finish()`. |
| `countVotes(target)` / `votedFor(voter)` / `hasVoted(voter)` | Data untuk lore GUI & tanda ✔. |
| `castVote(voter, target)` | Delegasi ke `plugin.castVote` (satu pintu). |
| `finish()` | Tally (`merge`+`sum`) → cari top & deteksi seri → broadcast hasil `Nama=N ... SKIP=N` → tutup semua GUI chest → `endMeeting(ejected)`; **seri / top==SKIP → ejected=null** (tidak ada yang diterusir). |

## 5. `VoteGUI.java` (GUI Voting)

| Method | Fungsi |
|--------|--------|
| `open(viewer)` | Inventory berukuran dinamis (`((alive+1)/9+1)*9`, max 54): satu `PLAYER_HEAD` per pemain hidup dengan `SkullMeta.setOwnerProfile` (kepala asli!), display name `✔ Nama` (hijau) kalau viewer sudah vote dia, lore "Vote: N" live; slot terakhir `STRUCTURE_VOID` berlabel SKIP. |
| `onClick(InventoryClickEvent)` | Filter via judul view == TITLE; semua klik di-cancel (GUI read-only); klik kepala → vote target (nama di-strip warna & tanda ✔); klik SKIP → vote null; keduanya closeInventory + `refreshAll()` — dijalankan `runTask` 1 tick kemudian biar aman dari aturan modifikasi inventory saat event. |
| `refreshAll()` | Buka ulang GUI semua pemain yang sedang melihat voting (deteksi via title di-strip startsWith "Voting") sehingga jumlah vote & tanda ✔ selalu segar. |

## 6. `SusCommand.java` & `VoteCommand.java`

- `SusCommand` — executor + tab completer `/sus`. Cek `sus.admin` manual ("Khusus admin."), switch per subcommand, helper `intOr` (parse angka aman), tab completion argumen 1 (semua subcommand) & argumen 2 (nama pemain online untuk add/remove), difilter prefix. Detail perilaku tiap subcommand di `03-sistem-command.md`.
- `VoteCommand` — `/vote`: tolak console, tolak jika bukan fase MEETING / belum ada vote aktif, tolak pemain mati/non-peserta, selain itu `voteGui.open(p)`.

## 7. `build.sh` + `sources.txt`

- `build.sh` — pipeline javac→copy resource→jar cf (rincian di `01-cara-pembuatan.md` §5).
- `sources.txt` — daftar path .java hasil `find src -name '*.java'`, dimakan javac lewat `@sources.txt`.

## 8. `src/plugin.yml`

| Key | Nilai | Arti |
|-----|-------|------|
| `name` | `SusSMP` | Nama plugin (nama folder config juga). |
| `main` | `id.kuru.sussmp.SusPlugin` | Class yang diinstansiasi server saat enable. |
| `version` | `1.4.2` | Versi, tampil di `/plugins` dan log. |
| `api-version` | `'1.21'` | Target API Paper 1.21. |
| `commands.sus` | usage | Command admin utama. |
| `commands.vote` | `/vote` | Buka GUI voting (hanya saat meeting). |
| `permissions.sus.admin` | default `op` | Server memblokir non-admin sebelum kode jalan; kode tetap cek ulang sebagai pengaman ganda. |

## 9. File Config (`plugins/SusSMP/config.yml`)

| Key | Default | Arti |
|-----|---------|------|
| `meeting.duration-seconds` | `60` | Durasi voting per meeting (detik). Diubah juga via `/sus meetingtime`. |
| `meeting.cooldown-seconds` | `300` | Jeda antar meeting/bell (5 menit). Diubah juga via `/sus cooldown`. |
| `impostors` | `1` | Jumlah impostor per game. Diubah juga via `/sus impostors`. Selalu di-clamp `1..(jumlah pemain-1)` saat start. |
| `win-bossbar` | `true` | Penanda saja — **belum dibaca kode**; bossbar saat ini selalu aktif. |
