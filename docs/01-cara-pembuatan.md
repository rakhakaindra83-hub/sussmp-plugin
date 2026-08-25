# 01 — Cara Pembuatan Plugin SusSMP

Dokumentasi proses pembuatan plugin **SusSMP v1.4.2** (Paper 1.21) dari nol sampai JAR siap deploy.

---

## 1. Spesifikasi Tujuan

Social deduction ala Among Us di Minecraft, dirancang untuk maksimal 10 pemain:

| # | Fitur | Tujuan |
|---|-------|--------|
| 1 | Role acak IMPOSTOR vs INNOCENT | Inti sosial deduction — siapa pembunuhnya |
| 2 | Animasi rolling role ala mesin slot | Momen dramatis saat mulai, role tidak bocor duluan |
| 3 | 4 task bos + kunci The End | Tujuan bersih crew; Ender Dragon selalu finale |
| 4 | Emergency Bell + meeting/vote | Diskusi & terusir mencurigakan ala Among Us |

Prasyarat desain: Paper 1.21 (`api-version: '1.21'`), paket `id.kuru.sussmp`, tanpa Maven/Gradle, bisa di-reload tanpa restart server.

## 2. Persiapan Environment (Windows)

Alat yang dipakai (semua tanpa Maven/Gradle):

| Alat | Lokasi | Fungsi |
|------|--------|--------|
| JDK 21 | `%LOCALAPPDATA%\tools\jdk-21*` | Wajib — MC 1.20.5+ butuh Java 21 |
| paper-api 1.21.8 | `%LOCALAPPDATA%\tools\mc-libs\paper-api-1.21.8.jar` | API Bukkit/Paper untuk compile |
| Adventure jars | `mc-libs\adventure-api-4.17.0.jar`, `adventure-key-4.17.0.jar`, `examination-api-1.3.0.jar`, `bungeecord-chat-1.20-R0.1.jar` | Compile-only, ikut `-cp` karena paper-api memakai tipe Adventure di signature-nya. TIDAK di-shade ke JAR (server sudah menyediakannya) |

Download paper-api dari repo PaperMC; adventure/bungeecord-chat dari Maven Central.

## 3. Struktur Proyek

```
sussmp-plugin/
├── build.sh                      ← script build (javac + jar)
├── sources.txt                   ← hasil find *.java (dibuat ulang tiap build)
├── src/
│   ├── plugin.yml                ← metadata plugin (HARUS di root JAR)
│   ├── config.yml                ← config default
│   └── main/java/id/kuru/sussmp/
│       ├── SusPlugin.java        ← main class: alur game, bell, bossbar, event bos
│       ├── SusCommand.java       ← executor + tab completer /sus
│       ├── GameRulesListener.java← kunci End, death, quit
│       ├── ChatVoteListener.java ← vote lewat chat (!vote / !skip)
│       ├── MeetingVote.java      ← mesin voting + timer meeting
│       ├── VoteGUI.java          ← GUI kepala pemain untuk vote
│       └── VoteCommand.java      ← /vote buka GUI
└── classes/                      ← output compile (di-rm tiap build)
```

Keputusan desain: **tanpa Maven** — untuk plugin sekecil ini JDK saja cukup.

## 4. Langkah Pembuatan

### Langkah 1 — plugin.yml
File pertama yang dibuat. Menentukan nama `SusSMP`, versi `1.4.2`, main class `id.kuru.sussmp.SusPlugin`, `api-version: '1.21'`, registrasi dua command (`sus`, `vote`) dan permission `sus.admin` (default: op). Detail isi di `02-fungsi-per-code.md` §8.

### Langkah 2 — Main class `SusPlugin`
Turunan `JavaPlugin` sekaligus `Listener`. Memegang seluruh state game:
- `Phase { LOBBY, PLAYING, MEETING, ENDED }` — mesin status tunggal yang dicek hampir semua handler.
- Koleksi pemain: `players` (Set UUID), `alive` & `impostor` (Map UUID→Boolean), `preMeeting` (posisi sebelum TP meeting), `tasksLeft` (4 NamespacedKey bos).
- Setting runtime: `meetingDuration`/`meetingCooldown` (detik), `impostorCount`, `lastMeeting` (tick gameTime terakhir meeting).

Alur `onEnable()`:
1. `saveDefaultConfig()` + baca 3 key config (`meeting.duration-seconds`, `meeting.cooldown-seconds`, `impostors`).
2. Registrasi diri sendiri sebagai listener (bell + EntityDeathEvent bos).
3. Buat `VoteGUI`, `ChatVoteListener`, `GameRulesListener` — ketiganya mendaftarkan diri di constructor masing-masing.
4. Pasang executor + tab completer `/sus` (`SusCommand`) dan executor `/vote` (`VoteCommand`).

Alur `onDisable()`: `bossbar.removeAll()` — supaya bossbar tidak nyangkut di layar pemain saat `/reload` atau shutdown.

### Langkah 3 — Alur mulai game di SusPlugin
`startGame()` → validasi minimal 2 pemain → bersihkan inventory + set Survival/HP penuh → countdown 3-2-1 (judul kuning bold tiap 20 tick) → `revealRolesAndBegin()`: acak impostor sesuai `impostorCount` (di-clamp 1..n-1), lalu `rollThenReveal()` → `revealImpostors()` → `beginPlay()` yang memasang task bos, bagikan bell, buat bossbar, dan set fase `PLAYING`. Rincian tiap method di `02-fungsi-per-code.md`.

### Langkah 4 — Listener GameRulesListener
Tiga proteksi aturan main:
- `onPortal` (PlayerPortalEvent): blokir `END_PORTAL`/`END_GATEWAY` selama `dragonUnlocked()` false → The End efektif terkunci sampai 3 bos lain tumbang.
- `onDeath` (PlayerDeathEvent): buang bell dari drop (`removeIf isBell`), bungkam death message, lalu cabang kill-by-impostor (`killPlayer`) vs mati alami.
- `onQuit`: keluar saat game jalan = dinyatakan mati + cek win; keluar saat lobby = keluar dari daftar pemain.

### Langkah 5 — Sistem meeting & vote
Empat file yang saling melengkapi:
- `ChatVoteListener` menangkap `!vote <nama>` / `!skip` di chat (priority LOWEST, pesan dibatalkan agar tidak bocor ke chat umum).
- `MeetingVote` menyimpan `votes` (voter→target, null = skip) + `eligible` (yang hidup), menjalankan timer detik-per-detik, dan menghitung hasil di `finish()` (seri/semua-skip = tidak ada yang diterusir).
- `VoteGUI` menampilkan kepala pemain hidup (lore berisi jumlah vote live) + slot SKIP `STRUCTURE_VOID`; klik = vote, GUI auto-refresh.
- `VoteCommand` membuka kembali GUI itu lewat `/vote` — hanya saat fase MEETING.

### Langkah 6 — File config
Satu YAML (`config.yml`) dengan komentar bahasa Indonesia langsung di dalam file. Key `win-bossbar` ada sebagai penanda tapi belum dibaca kode — bossbar selalu aktif.

### Langkah 7 — Command admin
Subcommand `add|remove|start|stop|meetingtime|cooldown|impostors|setroom|reload` di `SusCommand` dengan tab completion. Lengkap di `03-sistem-command.md`.

## 5. Build

`build.sh` menjalankan 5 hal:

```bash
# 0. Cari JDK 21 & susun classpath (pemisah ';' khas Windows)
TOOLS="$LOCALAPPDATA/tools"
JDK21=$(ls -d "$TOOLS"/jdk-21* | head -1)
CP="$LIBS/paper-api-1.21.8.jar;$LIBS/adventure-api-4.17.0.jar;..."

# 1. Bersihkan & kumpulkan semua path .java
rm -rf classes && mkdir -p classes
find src -name '*.java' > sources.txt

# 2. Compile (Java 21, classpath paper-api + adventure)
"$JDK21/bin/javac" --release 21 -encoding UTF-8 -cp "$CP" -d classes @sources.txt

# 3. Salin resource ke root classes/
cp src/plugin.yml src/config.yml classes/

# 4. Pack JAR — plugin.yml WAJIB di root JAR
"$JDK21/bin/jar" cf sussmp-1.4.2.jar -C classes .
```

Catatan penting:
- `-encoding UTF-8` wajib karena pesan plugin memakai karakter Unicode (mis. `…`).
- `plugin.yml` + `config.yml` harus di **root** JAR — salah letak, server tidak mengenali plugin.
- Classpath pakai pemisah `;` (Windows); script hanya jalan di bash/git-bash.
- `set -e` di atas: satu langkah gagal = build berhenti, tidak ada JAR setengah jadi.

Hasil: `sussmp-1.4.2.jar`. Jalankan dengan `bash build.sh`.

## 6. Deploy

Salin JAR ke folder `plugins/` server Paper 1.21.x → restart. Folder `plugins/SusSMP/` akan terisi `config.yml` otomatis pada boot pertama (`saveDefaultConfig()`).

## 7. Catatan Perilaku yang Terverifikasi di Kode

- Batas bawah pemain **2** (`startGame()` menolak kurang dari itu); batas atas 10 adalah target desain, kode tidak memasang cap keras.
- `reset()` dipanggil otomatis 10 detik setelah CREW/TRAITOR WIN, oleh `/sus stop`, dan aman juga saat `/reload` karena `onDisable()` membersihkan bossbar.
- `lastMeeting = -1` di `reset()` → cooldown bell selalu fresh di game baru.
