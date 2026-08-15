# Printer (fără reclame)

Aplicație Android nativă (Java) pentru printat pe imprimante WiFi de birou —
descoperire automată prin mDNS/Bonjour și trimitere prin **IPP / IPP Everywhere
(AirPrint)**. Fără reclame, fără conturi, fără trafic către servere externe:
tot ce face aplicația se întâmplă între telefon și imprimanta din rețeaua locală.

## Ce poate printa

| Sursă | Cum e tratată |
|---|---|
| PDF | trimis ca atare, după verificarea antetului `%PDF-` |
| Imagini (JPEG/PNG/WebP/HEIF) | randate într-un PDF, o imagine pe pagină, cu rotația EXIF aplicată |
| Text (`text/*`, JSON, XML) | paginat cu `StaticLayout`, cu antet și număr de pagină |
| Pagini web | încărcate într-un `WebView` off-screen și desenate în PDF, felie cu felie |

Documentele intră în aplicație fie din ecranul principal, fie prin **Share** din
altă aplicație (`ACTION_SEND`), fie prin deschiderea unui PDF (`ACTION_VIEW`).

## Funcții

- Descoperire automată `_ipp._tcp` și `_ipps._tcp` cu `NsdManager`, cu multicast lock
- Adăugare manuală după IP/host (pentru rețele fără mDNS), cu buton de test
- Preview paginat al PDF-ului final (`PdfRenderer`), cu navigare între pagini
- Opțiuni citite din imprimantă: format hârtie, față/verso, color, calitate, copii
- Orientare portret/peisaj: documentele generate sunt randate în orientarea aleasă,
  iar jobul trimite și `orientation-requested` (singurul efect pentru PDF-urile
  trimise ca atare)
- **Print Service de sistem**: după activarea din Settings → Printing, butonul
  „Print" din orice aplicație ajunge la imprimantele găsite de noi, cu dialogul și
  preview-ul native Android. Documentul vine gata ca PDF și trece prin aceeași
  conductă ca ecranul propriu
- **Raster pentru imprimantele fără interpretor de PDF**: `image/urf` (ce trimite
  AirPrint) și `image/pwg-raster`. Paginile se randează cu `PdfRenderer` în benzi de
  256 de linii și se comprimă PackBits pe pixeli întregi. Formatul se alege automat
  din `document-format-supported`, dar poate fi și forțat din ecranul de print,
  pentru imprimantele care nu răspund la Get-Printer-Attributes
- Interval de pagini (`1-3, 5`), trimis ca `page-ranges` (rangeOfInteger)
- Nivel de toner/cerneală din atributele `marker-*`
- Progres la upload, urmărirea stării jobului și **anulare** (`Cancel-Job`)
- Temă Material 3, mod întunecat

## Arhitectură

```
com.noads.printer
├── ipp/            client IPP scris de la zero (RFC 8010 / 8011), fără dependențe
│   ├── Ipp                 constante: operation-id, tag-uri, status, job-state
│   ├── IppRequest          encoder binar pentru cereri
│   ├── IppResponse         parser, inclusiv colecții imbricate
│   ├── IppClient           transport HTTP(S), Print-Job / Validate-Job / Cancel-Job
│   └── JobOptions          setările unui job
├── discovery/      PrinterDiscovery — mDNS prin NsdManager, cu coadă de resolve
├── model/          Printer, PrinterCapabilities, PrinterRepository (persistență)
├── render/         PageGeometry, ImageToPdf, TextToPdf, WebToPdf
├── raster/         RasterWriter (PackBits), UrfWriter, PwgRasterWriter, PdfToRaster
├── print/          PrintSource, DocumentPreparer, PrintJobManager,
│                   NoAdsPrintService + IppPrinterDiscoverySession (plugin de sistem)
├── util/           DocumentUtils, PageRanges, MediaNames
└── ui/             MainActivity, AddPrinterActivity, PrintJobActivity
```

Fluxul unui job: `PrintSource` → `DocumentPreparer` (PDF în cache) →
`PrintJobManager.submit()` → `IppClient.printJob()` → polling `Get-Job-Attributes`
până la o stare finală.

Stratul IPP nu depinde de Android în afară de `android.net.Uri` și `android.util.Log`,
deci poate fi testat separat.

## Download

APK-ul de debug se construiește automat la fiecare push pe `main` și e publicat la:

- pagina de download: <https://printer.d.ocl.ro/>
- fișierul direct: <https://printer.d.ocl.ro/a.apk>

Linkul apare și în lista centrală de pe <https://d.ocl.ro>.

## CI/CD

`.woodpecker.yml` (Woodpecker, host CI `10.10.10.101`):

| Eveniment | Ce face |
|---|---|
| pull request | build gate — construiește doar stage-ul `apk` din `Dockerfile` |
| push pe `main` | build complet + redeploy pe slotul `:18056`, cu health check |

`Dockerfile` are două etaje: `apk` (Android SDK 35 + Gradle 8.9, `assembleDebug`) și
imaginea finală nginx care servește `web/index.html` și `/a.apk`. Imaginea se
construiește pe host-ul de deploy prin `docker.sock` montat — fără registry.

Vhost-ul central e în `ocl-infra/redirects.csv`:
`printer.d.ocl.ro,http://10.10.10.101:18056/`.

APK-ul e semnat cu `app/debug.keystore`, commitat în repo. E cheia standard de debug
(parolă `android`), nu un secret; rostul ei e semnătura **stabilă**, ca update-urile
să se instaleze peste versiunea deja existentă pe telefon. Un keystore auto-generat
la fiecare build din Docker ar da altă semnătură de fiecare dată.

## Build

În mediul acesta nu există Android SDK, deci proiectul **nu a fost compilat aici** —
a fost verificat doar sintactic cu `javac`. Pentru build:

```bash
# Cu Android Studio: File > Open pe directorul proiectului.
# Din linia de comandă (necesită Android SDK + ANDROID_HOME setat):
gradle wrapper          # o singură dată, generează gradlew + gradle-wrapper.jar
./gradlew assembleDebug
./gradlew installDebug
```

`gradle/wrapper/gradle-wrapper.jar` nu este inclus (e un binar); `gradle wrapper`
sau Android Studio îl generează.

- `minSdk 24`, `targetSdk 35`, Java 17
- Nicio permisiune periculoasă: doar `INTERNET`, `ACCESS_NETWORK_STATE`,
  `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`. Fișierele se aleg prin
  Storage Access Framework, deci nu e nevoie de permisiuni de stocare.

## Limitări cunoscute

- **PWG Raster e scris după specificație, dar netestat pe hardware.** URF e
  confirmat pe Xerox WorkCentre 3025; PWG Raster a fost refuzat acolo pentru că
  imprimanta nu îl listează, deci antetul lui de 1796 de octeți e verificat doar
  cu un decodor propriu, nu de o imprimantă reală.
- **Fără autentificare IPP.** Un `HTTP 401` de la imprimantă e raportat ca atare;
  nu există UI de user/parolă.
- **`ipps://` acceptă certificate self-signed.** Imprimantele nu au certificate
  semnate de un CA, iar alternativa e HTTP în clar. Verificarea e relaxată doar
  pentru aceste conexiuni din LAN — vezi comentariul din `IppClient`.
- **Trafic în clar permis global** (`network_security_config.xml`): IPP pe portul
  631 e HTTP simplu, iar IP-urile din LAN nu pot fi enumerate dinainte.
- Iconița de lansare este un vector generat aici; înlocuiește-o cu un set PNG
  real (Image Asset Studio) înainte de publicare.
- Nu există încă teste unitare. Candidații evidenți: `IppRequest`/`IppResponse`
  (round-trip pe octeți) și `PageRanges`.
