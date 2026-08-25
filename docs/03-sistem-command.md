# 03 — Sistem Command SusSMP

Referensi lengkap semua perintah, sintaks, permission, dan perilakunya.

---

## Ringkasan

Plugin punya **dua command**: satu command admin dengan 9 subcommand + satu command pemain.

```
/sus <add|remove|start|stop|meetingtime|cooldown|impostors|setroom|reload>
/vote
```

| Subcommand | Sintaks | Fungsi | Butuh target/nilai |
|------------|---------|--------|--------------------|
| `add` | `/sus add <pemain>` | Masukkan pemain online ke lobby | Ya (nama pemain) |
| `remove` | `/sus remove <pemain>` | Keluarkan dari lobby | Ya (nama pemain) |
| `start` | `/sus start` | Mulai game (countdown → rolling role → main) | — (min. 2 pemain di lobby) |
| `stop` | `/sus stop` | Hentikan game berjalan + `reset()` penuh | — |
| `meetingtime` | `/sus meetingtime <detik>` | Durasi voting tiap meeting (min. 10), persisten ke config | Ya |
| `cooldown` | `/sus cooldown <detik>` | Cooldown antar bell/meeting (min. 30), persisten ke config | Ya |
| `impostors` | `/sus impostors <jumlah>` | Jumlah impostor (min. 1), persisten ke config | Ya |
| `setroom` | `/sus setroom` | Tandai pusat ruang meeting di posisi eksekutor | Harus dari dalam game |
| `reload` | `/sus reload` | Muat ulang config tanpa restart | — |

`/vote` — buka GUI voting; hanya bisa dipakai **saat meeting** dan oleh pemain hidup yang ikut game.

## Permission

| Permission | Default | Mengatur |
|-----------|---------|----------|
| `sus.admin` | `op` | SELURUH subcommand `/sus`. Dicek di baris pertama `onCommand` (`Khusus admin.`); plugin.yml juga mendeklarasikannya. |

`/vote` tidak butuh permission apa pun — validasinya kontekstual (fase MEETING + peserta hidup).

Beri ke admin lain: `/lp user <nama> permission set sus.admin true` (LuckPerms) atau `/op`.

## Detail Per Subcommand

### `/sus add <pemain>` / `/sus remove <pemain>`

- Target dicari via `Bukkit.getPlayerExact` — harus **online** & nama persis.
- `add` ditolak saat fase bukan LOBBY ("Game sedang jalan."); sukses → "Nama masuk (N pemain)."
- `remove` diam-diam menghapus UUID-nya; tidak error kalau target offline/tidak terdaftar.
- Belum ada cap keras 10 pemain di kode — batas itu disiplin lobby manual.

### `/sus start`

- Panggil `startGame()`: kurang dari 2 pemain → "Minimal 2 pemain." dan game tidak jalan.
- Urutan otomatis: bersihkan inventory semua peserta → set Survival/HP 20/makanan 20 → countdown 3-2-1 → rolling role ±3 detik → reveal IMPOSTOR (merah, tampil terakhir) & INNOCENT (hijau) → bagikan bell → bossbar muncul → fase PLAYING.

### `/sus stop`

- Diabaikan kalau masih LOBBY (tidak ada yang perlu dihentikan).
- Selain itu panggil `reset()`: gamemode dipulihkan, inventory+armor dibersihkan, potion effect dilepas, bossbar hilang, `lastMeeting=-1`, task kosong → "Game dihentikan."

### `/sus meetingtime <detik>` / `/sus cooldown <detik>` / `/sus impostors <jumlah>`

- Nilai dibaca lewat `intOr`; gagal parse / di bawah minimum → cetak usage subcommand itu:
  - `meetingtime`: min **10** detik.
  - `cooldown`: min **30** detik.
  - `impostors`: min **1**.
- Nilai valid langsung aktif di runtime **dan** ditulis balik ke `config.yml` (`saveConfig()`) — bertahan restart.
- Contoh: `/sus cooldown 120` → bell maksimal sekali per 2 menit; balasan "Cooldown bell = 120 dtk."
- Catatan: jumlah impostor tetap di-clamp `1..(pemain-1)` saat start, jadi setting besar tidak bikin semua orang impostor.

### `/sus setroom`

- Hanya bisa dari dalam game (bukan console): simpan lokasi eksekutor sebagai pusat ruang meeting.
- Dipakai `openMeeting()` untuk TP pemain ke kursi melingkar radius 3 blok. Kalau belum diset, pusat meeting = posisi pemain yang memicu meeting saat itu.

### `/sus reload`

- `reloadConfig()` lalu baca ulang ketiga key (`meeting.duration-seconds`, `meeting.cooldown-seconds`, `impostors`) ke field runtime.
- Aman dipakai kapan saja termasuk mid-game untuk durasi/cooldown/impostor (impostor baru berlaku di game berikutnya karena role sudah terbagi).

### `/vote`

| Kondisi | Hasil |
|---------|-------|
| Console | "Khusus pemain." |
| Fase bukan MEETING / vote tidak aktif | "Vote GUI hanya bisa dibuka saat meeting!" |
| Bukan peserta atau sudah mati | "Kamu tidak ikut voting." |
| Valid | GUI kepala pemain hidup terbuka (sama seperti yang auto-muncul saat meeting mulai). |

## Contoh Alur Pemakaian Nyata

Setup lobby sampai game selesai:

```text
# 0. Persiapan sekali saja
/sus setroom                     # berdiri di tengah ruang meeting
/sus impostors 2                 # game 8 orang, 2 impostor
/sus meetingtime 45              # voting cepat 45 detik
/sus cooldown 180                # bell boleh tiap 3 menit

# 1. Kumpulkan peserta
/sus add Andi
/sus add Budi
/sus add Cika                    # ...sampai 10 pemain
/sus remove Cika                 # Cika ikut pulang? keluarkan lagi

# 2. Mulai
/sus start                       # countdown 3-2-1 → rolling role ±3 dtk → reveal role
                                 # impostor dapat title merah paling akhir, sisanya hijau INNOCENT

# 3. Selama main (PLAYING)
#    Crew buru bos bebas urutan; bossbar "Bos tersisa: ..." turun n/4.
#    Warden mati → "[SUS] Warden tumbang! Task 1/4 selesai."
#    Bos ke-3 tumbang → "The End terbuka! Saatnya Ender Dragon." → portal End boleh dilalui.
#    Sebelum itu, masuk portal ditolak: "The End masih terkunci!"

# 4. Meeting
#    Pemain klik kanan Emergency Bell (atau admin menyuruh crew menemukan mayat):
#    semua yang hidup TP melingkar di ruang meeting, GUI voting muncul,
#    chat boleh dipakai: "!vote Budi" atau "!skip"
#    Budi terkumpul suara terbanyak (tanpa seri) → diterusir → jadi spectator,
#    statusnya diumumkan IMPOSTOR/INNOCENT. Seri atau mayoritas skip = tidak ada yang diterusir.

# 5. Akhir game (otomatis)
#    Semua impostor mati/diterusir  → CREW WIN   → bossbar hilang → reset otomatis 10 dtk
#    Crew hidup <= impostor hidup   → TRAITOR WIN → idem
#    Ender Dragon tumbang           → CREW WIN (4/4 task)

# 6. Darurat
/sus stop                        # batalkan mid-game; reset penuh, lobby siap /sus start lagi
/sus reload                      # habis edit config.yml manual
```

## Tab Completion

Ada di `SusCommand.onTabComplete`:

- Argumen 1: daftar lengkap `add, remove, start, stop, meetingtime, cooldown, impostors, setroom, reload`.
- Argumen 2 hanya untuk `add`/`remove`: semua nama pemain online.
- Semua saran difilter prefix yang diketik (`/sus co<TAB>` → `cooldown`). `/vote` tidak punya tab completion.

## Pesan Error & Edge Case

| Situasi | Pesan / Perilaku |
|---------|------------------|
| Non-admin pakai `/sus` | `Khusus admin.` |
| Tanpa subcommand / salah ketik | Usage: `/sus add\|remove\|start\|stop\|meetingtime\|cooldown\|impostors\|setroom\|reload` |
| `add` target offline | `Pemain offline.` |
| `add` saat game jalan | `Game sedang jalan.` |
| `start` < 2 pemain | `Minimal 2 pemain.` |
| `meetingtime 5` / `cooldown 10` / `impostors 0` | Usage subcommand tsb (di bawah minimum). |
| `setroom` dari console | `Dari dalam game.` |
| Bell diklik saat bukan PLAYING | `Game belum jalan.` |
| Bell diklik saat cooldown | `Meeting cooldown! Tunggu N dtk lagi.` |
| `!vote` ke pemain mati/offline/bukan peserta | `Tidak bisa vote <nama>.` (suara tidak tercatat, pesan tak bocor ke chat) |
| Vote seri / semua skip | `Vote skip — tidak ada yang diterusir.` |
