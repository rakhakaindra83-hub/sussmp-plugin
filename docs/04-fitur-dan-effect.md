# 04 — Fitur & Effect In-Game SusSMP

Penjelasan gameplay: apa yang dirasakan pemain/admin dari setiap fitur, termasuk efek sampingnya.

---

## Fitur 1 — Animasi Rolling Role (Mesin Slot)

**Kode:** `SusPlugin.rollThenReveal()` + `revealImpostors()` — dipicu `/sus start` setelah countdown 3-2-1.

### Cara kerja singkat
Setelah hitungan 3-2-1 (judul kuning bold tiap detik), semua pemain melihat judul berganti nama role acak dari pool 9 label (`DETECTIVE, MEDIC, ENGINEER, SNIPER, SPY, JESTER, VETERAN, IMPOSTOR, INNOCENT`):

- **15 kali ganti dalam ±3 detik**: 10 ganti cepat berjarak 2 tick, lalu 5 ganti makin lambat (jeda 4, 6, 8, 10, 12 tick) — efek mesin slot yang melambat.
- Tiap ganti bunyi klik tombol (`UI_BUTTON_CLICK`) dengan pitch naik bertahap `0.75 + idx*0.04` — makin lama makin "menegang".
- Setelah itu reveal asli per orang: impostor satu-satu (tiap 1,5 detik) dapat title merah `IMPOSTOR` + suara detak jantung Warden; baru kemudian sisanya hijau `INNOCENT`.

### Effect in-game

| Aspek | Efek |
|-------|------|
| Kerahasiaan role | Semua orang lihat animasi sama, jadi tidak ada yang bisa membaca role temannya dari reaksi layar. Impostor selalu tampil terakhir → dramatis. |
| Nama di rolling | Murni kosmetik — nama seperti MEDIC/SNIPER **tidak** memberi kemampuan apa pun; hanya ada dua role nyata: IMPOSTOR & INNOCENT. |
| Suara | Klik yang pitch-nya merambat naik + heartbeat untuk impostor = sinyal audio khas momen reveal. |

**Config terkait:** tidak ada — durasi & kecepatan hardcoded (±3 detik). Jumlah impostor yang menentukan siapa yang di-reveal: `impostors`.

---

## Fitur 2 — Task Bos Bebas Urutan

**Kode:** `SusPlugin.beginPlay()`, `onBossKill()`, `bossKilled()`.

### Cara kerja singkat
Saat game mulai, `tasksLeft` diisi 4 key: `t_elder`, `t_warden`, `t_wither`, `t_dragon`. Handler `EntityDeathEvent` memetakan EntityType→key dan hanya menghitung jika pembunuhnya peserta game:

```java
if (tasksLeft.isEmpty()) { crewWin(); return; }   // dragon mati = task selesai semua
updateBar();
if (tasksDone == 3) broadcast("The End terbuka! Saatnya Ender Dragon.");
```

Tiga bos pertama boleh diburu **dalam urutan apa pun**; progres dicatat global (n/4), bukan per urutan.

### Effect in-game

| Aspek | Efek |
|-------|------|
| Strategi crew | Bebas pilih bos termudah dulu (mis. Elder Guardian) tanpa penalti; bossbar tetap akurat karena progres total. |
| Kill dobel / bos spawn ulang | `onBossKill` cek `tasksLeft.contains(hit)` — key yang sudah dihapus tidak dihitung lagi. |
| Syarat kill sah | Pembunuh harus pemain terdaftar; bos yang mati sendiri (mis. Wither menyeberang chunk aneh) tak dihitung sampai pemain membunuhnya. |

**Config terkait:** tidak ada — daftar bos fixed.

---

## Fitur 3 — Kunci The End (Ender Dragon Harus Terakhir)

**Kode:** `GameRulesListener.onPortal()` + `SusPlugin.dragonUnlocked()`.

### Cara kerja singkat

```java
boolean dragonUnlocked() { return tasksDone >= 3; }
// onPortal: cause END_PORTAL / END_GATEWAY saat game jalan
if (!plugin.dragonUnlocked()) { e.setCancelled(true); /* pesan */ }
```

Selama belum ada 3 bos tumbang, setiap percobaan masuk End (portal frame pakai mata Ender maupun end gateway) dibatalkan dengan pesan merah "The End masih terkunci! Tumbangkan dulu 3 bos lainnya."

### Effect in-game

| Aspek | Efek |
|-------|------|
| Finale terjamin | Dragon tidak bisa diburu duluan walau pemain sudah punya blaze rod + pearl sejak menit pertama. |
| Cakupan | Aktif saat PLAYING dan MEETING; setelah game selesai (ENDED/LOBBY) portal normal vanilla. |
| Nether aman | Hanya portal End yang dikunci — portal Nether lewat bebas. |

**Config terkait:** tidak ada — ambang tetap 3 bos.

---

## Fitur 4 — Emergency Bell

**Kode:** `SusPlugin.bellItem()`, `isBell()`, event `onInteract/onBellDrop/onBellClick/onBellDrag`, `GameRulesListener.onDeath()`.

### Cara kerja singkat
Tiap pemain dapat 1 bell bernama emas "Emergency Bell" saat game mulai. Klik kanan → meeting darurat (kalau fase PLAYING & cooldown lewat). Bell dikunci total:

| Aksi | Hasil |
|------|-------|
| Drop (Q) | Dibatalkan |
| Pindah slot / shift-klik ke chest/hopper | Dibatalkan (cek current & cursor item) |
| Drag antar-slot | Dibatalkan |
| Pemilik mati | `drops.removeIf(plugin::isBell)` — bell lenyap bersama mayat, tidak nyempil di tanah |

### Effect in-game
- Tidak ada exploit "numpuk bell buat spam meeting" atau "jual bell".
- Mati = bell hilang → crew makin sedikit alat panggil meeting, tekanan sosial naik.
- Cooldown dihitung dari gameTime dunia (`lastMeeting`), ditampilkan sisa detiknya kalau masih panas.

**Config terkait:** `meeting.cooldown-seconds` (default 300). Cooldown ikut reset tiap game selesai karena `reset()` menyetel `lastMeeting = -1`.

---

## Fitur 5 — Meeting & Voting (Chat + GUI)

**Kode:** `openMeeting()`, `MeetingVote`, `VoteGUI`, `ChatVoteListener`, `VoteCommand`.

### Cara kerja singkat
Meeting dimulai dari klik bell ("darurat!") — atau via kode dengan alasan "menemukan mayat!". Yang terjadi otomatis:

1. Fase → MEETING; `lastMeeting` dicatat (cooldown mulai).
2. Pemain **hidup** TP ke kursi melingkar radius 3 blok di ruang meeting (posisi asli disimpan); yang mati tidak ikut.
3. GUI voting muncul 5 tick kemudian: kepala asli tiap pemain hidup (lore "Vote: N" live, ✔ hijau di kepala pilihanmu sendiri) + slot SKIP (`STRUCTURE_VOID`) di ujung.
4. Vote dua jalur: klik kepala/SKIP di GUI, atau chat `!vote <nama>` / `!skip` (pesan vote dibatalkan sehingga tidak bocor ke chat umum — diskusi tetap bebas).
5. Timer per detik sesuai `meeting.duration-seconds`; umumkan tiap kelipatan 10 detik + title countdown 5 detik terakhir. Selesai lebih awal kalau semua yang eligible sudah vote.
6. Hasil diumumkan `Nama=N ... SKIP=N`; **terbanyak = diterusir, seri atau mayoritas SKIP = tidak ada yang diterusir**. GUI tertutup otomatis.
7. Yang diterusir: SPECTATOR, title `DITERUSIR` + pengumuman dia IMPOSTOR (merah) atau INNOCENT (hijau); semua pemain balik ke posisi semula.

### Effect in-game

| Aspek | Efek |
|-------|------|
| Diskusi vs vote | Chat tidak pernah dimatikan — pemain bisa debat dulu, vote diam-diam lewat GUI/chat command. |
| Satu orang satu suara | Map voter→target; vote ulang menimpa suara lama sampai waktu habis. |
| Spectator netral | Yang mati/diterusir tidak masuk daftar `eligible` — tidak bisa memengaruhi hasil. |

**Config terkait:** `meeting.duration-seconds` (default 60), `meeting.cooldown-seconds` (default 300).

---

## Fitur 6 — Win Condition

**Kode:** `checkWinConditions()`, `crewWin()`, `traitorWin()`. Dipanggil dari: kill, mati alami, quit mid-game, dan hasil meeting.

```java
if (aliveImp == 0)              crewWin();
else if (aliveCrew <= aliveImp) traitorWin();
```

### Effect in-game

| Skenario | Hasil |
|----------|-------|
| Semua impostor mati/diterusir | Title besar hijau **CREW WIN** + chat broadcast |
| Crew hidup ≤ impostor hidup (contoh 8 pemain, 2 impostor: tinggal 2 crew vs 2 impostor) | Title merah **TRAITOR WIN** — impostor dominan secara angka |
| Dragon tumbang sebagai task ke-4 | CREW WIN lewat jalur `bossKilled()` |

Guard `phase == ENDED` membuat win tidak dobel-trigger walau dua kondisi kejadian hampir bersamaan.

**Config terkait:** jumlah impostor (`impostors`) menentukan seberapa cepat ambang TRAITOR WIN tercapai.

---

## Fitur 7 — Reset Bersih (Auto & Manual)

**Kode:** `scheduleReset()` (10 detik setelah win), `reset()`, `stop`, dan `onDisable()`.

### Cara kerja singkat
Satu method `reset()` membersihkan semuanya: bossbar `removeAll()+null`, gamemode kembali Survival, inventory+armor dikosongkan, potion effect dilepas, map state kosong, **`lastMeeting = -1`**, task kosong, vote dibuang, fase LOBBY.

### Effect in-game

| Pemicu | Efek yang dirasakan pemain |
|--------|---------------------------|
| Menang/kalah | Layar hasil terbaca 10 detik → bossbar & title hilang sendiri → server bersih untuk ronde berikutnya. |
| `/sus stop` | Game batal di tengah jalan; tidak ada inventory sisa, bell sisa, atau potion sisa. |
| `/reload` / shutdown | `onDisable()` menghapus bossbar — tidak ada bar kuning nyangkut permanen. |
| Game berikutnya | Cooldown bell fresh (bawaan `lastMeeting=-1`) dan bossbar mulai dari 0/4. |

**Config terkait:** tidak ada — delay reset fixed 10 detik.

---

## Interaksi Antar-Fitur & Batasan

- Fase tunggal (`LOBBY→PLAYING→MEETING→ENDED`) adalah gerbang segalanya: bell cuma di PLAYING, vote cuma di MEETING, kill bos cuma di PLAYING.
- Death message dibungkam sepanjang game — identitas impostor tidak bocor dari feed kill.
- Keluar server mid-game dihitung mati; keluar saat lobby sekadar mengeluarkan dari daftar.
- Batas atas 10 pemain adalah target desain (kode hanya memvalidasi minimum 2); batas bawah setting: `meetingtime ≥ 10`, `cooldown ≥ 30`, `impostors ≥ 1`.
- Key `win-bossbar` di config belum dibaca kode — bossbar selalu aktif.

## Cheat-Sheet Admin Sehari-hari

```text
/sus setroom                 # sekali saja, di tengah ruang meeting
/sus impostors 2             # atur jumlah impostor
/sus add Andi                # ...ulangi tiap pemain
/sus start                   # mulai (min 2 pemain)
/sus cooldown 180            # bell tiap 3 menit saja
/sus meetingtime 45          # voting cepat
/sus stop                    # batal/reset mid-game
/sus reload                  # habis edit config.yml manual
```
