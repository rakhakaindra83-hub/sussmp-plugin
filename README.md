# SusSMP

Plugin Minecraft Paper/Spigot 1.21+ — social deduction ala Among Us untuk 10 pemain.
Terinspirasi video "10 YouTubers VS 1 Secret Liar" & "10 YouTubers vs 1 Secret Traitor".

## Cara main
1. `/sus add <pemain>` untuk tiap peserta (sampai 10), `/sus setroom` di lokasi ruang meeting.
2. `/sus start` → role acak: IMPOSTOR (bunuh semua) vs INNOCENT.
3. Task crew: bunuh **Elder Guardian → Warden → Wither → Ender Dragon** (urutan wajib, The End terkunci sampai 3 bos pertama tumbang).
4. Item **Emergency Bell** di hotbar — klik kanan untuk meeting (cooldown 5 menit, atur via config).
5. Meeting: semua TP ke ruang meeting, vote via chat `!vote <nama>` / `!skip` **atau GUI**:
   ketik `/vote` untuk buka jendela berisi kepala pemain yang masih hidup — klik = vote,
   slot SKIP di pojok. `/vote` hanya bisa dipakai saat waktu voting (meeting) saja.
   Durasi bisa diatur, yang diterusir ditandai kepala Steve + jadi spectator.

## Fitur
- Title role saat mulai (IMPOSTOR merah / INNOCENT hijau)
- Bossbar target bos aktif
- Death message dibungkam selama game
- Bell tidak bisa dibuang, didrop, atau dipindah ke chest
- Win: semua task / impostor terusir → CREW WIN (hijau); innocent habis → TRAITOR WIN (merah)
- Jumlah impostor bisa diatur: `/sus impostors <jumlah>`

## Perintah admin (`sus.admin` / op)
`sus add|remove|start|stop|meetingtime|cooldown|impostors|setroom`

## Build
Tanpa Gradle/Maven:

    bash build.sh   # javac + jar, butuh JDK21 & paper-api di $LOCALAPPDATA/tools/mc-libs
