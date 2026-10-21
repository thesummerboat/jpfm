# JPFM

Egen musikapp för Android ovanpå Plex – som Spotify, fast med din egen musiksamling. Fungerar i telefonen, i bilen via Android Auto och utan nät för det du laddat ner.

Syskonapp till [JPTV](https://github.com/thesummerboat/jptv).

## Vad som finns

| Del | Vad den gör |
| --- | --- |
| Inloggning | "Logga in med Plex" öppnar webbläsaren, eller skriv koden på [plex.tv/link](https://plex.tv/link). Servern hittas automatiskt, lokal adress först. |
| Byter nät själv | Appen minns alla serverns adresser. Går du från wifi hemma till mobildata i bilen letar den upp fjärradressen och musiken fortsätter. |
| Hem | Filter överst (**Allt · Musik · Radio · Poddar**). Snabbval med det du spelat senast, radio, **Dina mixar** (en per genre du lyssnar mest på + dina favoriter), **Hett hos dig** (mest spelat senaste månaden, från Plex historik), nya poddavsnitt, **Hett i Sverige just nu** (topplistan – låtarna som finns i ditt bibliotek), nya släpp, Plex egna rader, **Återupptäck** och **Bläddra** bland genrer, stämningar och årtionden. |
| Mixar och kategorier | Varje genre, stämning (energiskt, fest, lugnt, melankoliskt …) och årtionde har en egen sida med albumen (mest spelade först) och en mix som blandar och fyller på av sig själv. |
| Bibliotek | Artister med bokstavslist vid kanten, album i rutnät med sortering (titel, artist, nyast tillagda, år), spellistor och genrer. |
| Artist | Stor bild, artistradio, blanda, populära låtar, album uppdelade i album/singlar och EP/live/samlingar, liknande artister och biografi. |
| Album och spellista | Färger från omslaget (Plex UltraBlur eller räknat från bilden), skivor, spela/blanda, ladda ner. |
| Spelarvy | Stort omslag som andas med musiken, svep för nästa/föregående, dra ner för att stänga, tidslinje med spolning, gilla, upprepa, blanda. |
| Låttext | Tidsatt text som följer med och går att trycka på för att hoppa dit (kräver Plex Pass och att servern hittar text). |
| Kö | Härnäst, svep bort låtar, tryck för att hoppa, spela härnäst och lägg i kö från alla menyer. |
| Radio | Plex stationer och mixar, artistradio och **låtradio** från vilken låt som helst med Plex soniska analys. Radion fylls på av sig själv och tar aldrig slut. |
| Ljud | Direktuppspelning av originalfilen utan glapp mellan låtar. Ljudutjämning med Plex ljudanalys (automatiskt per album eller per låt). Klarar telefonen inte filen kodar Plex om den automatiskt. Valfri lägre kvalitet på mobildata. |
| Offline | Ladda ner album och spellistor i originalkvalitet (bara på wifi om du vill). Omslag och ljudnivåer sparas också, så allt fungerar utan nät – även i bilen. |
| Android Auto | Hem (Dina mixar, topplistorna, stationer och Plex mixar direkt), Spellistor, Artister (A–Ö) och Nedladdat, sök i bilen och röststyrning: "Spela Kent på JPFM". Gilla och låtradio som knappar i bilen. |
| Riktiga omslag | Låtar på samlingsskivor ("Top 100 Hits", "Various Artists" …) får originalsingelns eller albumets omslag – i listor, spelaren, notisen och bilen. Letas upp i MusicBrainz/Cover Art Archive och hos Apple som reserv. Går att stänga av i inställningarna. |
| Önska musik | Saknas något? Sök och tryck **Önska** – från sök, från topplistan (låtar du inte har) eller under Inställningar. Önskningen går till JPFM-servern hemma; andras önskningar godkänner eller nekar du först. Lidarr laddar ner skivan, Plex läser in den och den dyker upp i JPFM. |
| Fortsätt där du var | Kön sparas, så musiken kan fortsätta efter omstart, från hörlurarna eller när bilen startar. |
| Plex vet vad som spelas | Spelas nu, senast spelat och antal spelningar uppdateras i Plex, och gillade låtar sparas där. |

## Installera

1. Öppna `https://github.com/thesummerboat/jpfm/releases/latest/download/jpfm.apk` på telefonen.
2. Tillåt webbläsaren att installera appar när telefonen frågar, och installera JPFM.

**Sedan uppdaterar JPFM sig själv.** Finns en ny version visas en ruta på hemskärmen – tryck **Uppdatera**.

Varje push till `main` bygger en ny version i GitHub Actions och publicerar den under **Releases** som `jpfm.apk`.

## Android Auto (Polestar 2 m.fl.)

Appar som inte kommer från Play Butik måste godkännas en gång i Android Auto:

1. Telefonen: **Inställningar → Anslutna enheter → Anslutningsinställningar → Android Auto** (eller öppna Android Auto-appen).
2. Rulla längst ner och tryck **tio gånger på Version** tills utvecklarläget slås på.
3. Menyn med tre prickar → **Utvecklarinställningar** → slå på **Okända källor**.
4. Koppla in telefonen med sladd i bilen. JPFM finns bland ljudapparna.

Polestar 2 har trådbunden Android Auto från programvaruversion P3.3.16.

## Önska musik: JPFM-servern och Lidarr på Unraid

Önskningar går via **JPFM-servern**, en liten container på Unraid. Den kollar med plex.tv vem som önskar: du som äger Plex-servern blir admin, de du delar biblioteket med kan önska (du godkänner eller nekar), alla andra nekas. Godkända önskningar skickas till [Lidarr](https://lidarr.audio), som laddar ner skivan dit Plex läser. Vad Lidarr laddar ner ifrån bestämmer du i Lidarr; JPFM är bara beställningsdelen.

### 1. Lidarr

1. Unraid → **Apps** → sök "Lidarr" (t.ex. linuxserver/lidarr) → installera. Peka `/music` i containern mot **samma musikmapp som Plex läser** (t.ex. `/mnt/user/media/music`) och `/downloads` mot nedladdningsmappen.
2. **Settings → Media Management → Root Folders:** lägg till `/music`.
3. **Settings → Profiles → Metadata Profiles:** bocka i *Album*, *EP* och *Single*, så går det att önska singlar.
4. **Settings → Download Clients:** lägg till din nedladdningsklient.
5. **Settings → Connect → Plex Media Server:** då läser Plex in ny musik direkt.
6. **Settings → General:** kopiera **API Key**.

### 2. JPFM-servern

1. Unraid → **Docker → Add Container**:
   - **Repository:** `ghcr.io/thesummerboat/jpfm-server:latest`
   - **Port:** `8687` → `8687`
   - **Path:** `/data` → `/mnt/user/appdata/jpfm-server`

   (Eller lägg [`server/unraid/jpfm-server.xml`](server/unraid/jpfm-server.xml) i `/boot/config/plugins/dockerMan/templates-user/` och välj mallen *jpfm-server*.)
2. **JPFM → Inställningar → Önska musik → JPFM-servern och Lidarr:** fyll i `http://<unraid>:8687` och tryck **Anslut**. Första gången kopplas servern till ditt Plex-konto (du måste äga Plex-servern).
3. Under **Lidarr** i samma vy: Lidarrs adress *sett från servern* (t.ex. `http://192.168.1.10:8686`), API-nyckeln → **Testa anslutningen** → välj mapp och profiler → **Spara**. Nyckeln sparas bara på servern.

### Dela med andra

De du delar Plex-biblioteket med installerar JPFM, loggar in med sitt Plex-konto och ansluter till samma serveradress. Deras önskningar hamnar under **Att godkänna** hos dig (och på hemskärmen) med **Godkänn** och **Neka** (med skäl om du vill). Under *De du delar med* kan du välja **Godkänn alltid** eller **Blockera** per person.

### Utanför hemmet (Tailscale)

1. Unraid 7: **Settings → Tailscale** (eller pluginet *Tailscale* i Apps) → logga in. Servern får ett namn, t.ex. `tower`.
2. Installera Tailscale på telefonen med samma konto.
3. Använd `http://tower:8687` i JPFM – det fungerar både hemma och borta. Utan Tailscale sparas önskningar och skickas när telefonen når servern.

## Signering

Alla versioner signeras med samma nyckel, annars vägrar Android installera uppdateringen ovanpå den gamla. Nyckeln ligger **krypterad** i repot (`app/jpfm.keystore.enc`). Lösenordet finns bara som hemligheten `JPFM_SIGNING_PASSWORD` under *Settings → Secrets and variables → Actions*. Utan hemligheten byggs appen ändå, men ingen release publiceras.

## Bygga själv

Öppna mappen i Android Studio, eller kör `./gradlew assembleDebug`. Kräver JDK 17 och Android SDK 35. Testa Android Auto med Desktop Head Unit (DHU) i Android Studio.

## Teknik

Kotlin · Jetpack Compose (Material 3) · Media3 (ExoPlayer, MediaLibraryService, nedladdning) · OkHttp · Coil · Palette. Plex-API enligt [developer.plex.tv](https://developer.plex.tv).

- `plex/` – inloggning, serverval med omförsök, musik-API och tolkning av svaren
- `request/` – önska musik via JPFM-servern: sök, önska, godkänn/neka, följ nedladdningen
- `server/` – JPFM-servern (Python/FastAPI, SQLite): Plex-inloggning, godkännanden, Lidarr
- `covers/` – riktiga omslag för låtar på samlingsskivor
- `charts/` – topplistan för Sverige (Apple Musics publika lista, utan konto)
- `playback/` – spelartjänsten, köer och radio, Android Auto-trädet, ljudutjämning, omslag till bilen
- `download/` – nedladdning för offline
- `lyrics/` – låttext (LRC, Plex JSON/XML)
- `ui/` – gränssnittet
