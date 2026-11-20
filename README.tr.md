# muffuqOS

Android TV'ler için hızlandırma paketi + modern bir ana ekran (launcher).

TV'nin yazılımı değiştirilmez: bootloader ve firmware dokunulmadan kalır. muffuqOS gereksiz servisleri **devre dışı bırakır**, ayarları ince ayarlar ve stok ana ekranın yerine kendi launcher'ını koyar. Her değişiklik kayıt altına alınır ve tek komutla geri alınabilir.

## Neler var

| Parça | Ne yapar |
|---|---|
| `launcher/` | muffuqOS ana ekranı. Kotlin + Jetpack Compose (tv-material). Odaktaki uygulamanın renklerini alan canlı arka plan, favoriler, sıralama, gizleme. 1 GB RAM'li TV'ler düşünülerek yazıldı. |
| `tools/muffuq.ps1` | Windows araç kutusu: TV'ye bağlan, durum raporu, hızlandır, launcher'ı kur, geri al. |
| `debloat/packages.txt` | Kapatılabilecek paketlerin listesi. Düz metin, istediğin gibi düzenleyebilirsin. |
| `muffuq.bat` | Araç kutusunu çift tıkla açar. |

## TV'yi hazırlama (bir kerelik)

1. TV'de **Ayarlar → Cihaz Tercihleri → Hakkında**
2. **Derleme** (Build) satırında OK'ye **7 kez** bas → "Artık geliştiricisiniz"
3. Geri gel → **Geliştirici seçenekleri** → **USB hata ayıklama**'yı aç (varsa *Ağ üzerinden hata ayıklama*'yı da)
4. TV'nin IP adresini not et: **Ayarlar → Ağ ve İnternet**
5. Araç ilk bağlandığında TV'de çıkan izin penceresinde **"Her zaman izin ver"**i işaretleyip onayla

Android 11+ TV'lerde 5555 portu kapalıysa menüdeki **"Eşleştirme koduyla bağlan"** seçeneğini kullan.

## Kullanım

`muffuq.bat`'a çift tıkla, menüden ilerle. Ya da doğrudan:

```powershell
.\tools\muffuq.ps1 -Command status   -Device 192.168.1.50
.\tools\muffuq.ps1 -Command speed    -Level recommended      # safe | recommended | aggressive
.\tools\muffuq.ps1 -Command launcher                          # -KeepStockLauncher: stok launcher'a dokunma
.\tools\muffuq.ps1 -Command restore  -UninstallLauncher       # her şeyi eski haline getir
```

`-Yes` eklersen soru sormadan varsayılanlarla ilerler.

### Hızlandır ne yapar

- `debloat/packages.txt`'teki, TV'de gerçekten yüklü olan paketleri listeler; seçtiklerini `pm disable-user` ile kapatır. Silmez.
- Animasyon hızını 0.5'e alır (menüler daha çevik hissettirir).
- Uygulama önbelleklerini temizler, arka plan süreçlerini kapatır.

Hazır ayarlar:

| Ayar | Kapsam |
|---|---|
| `safe` | Kimsenin fark etmeyeceği arka plan servisleri (geri bildirim, vitrin modu, takvim senkronu...) |
| `recommended` | + önyüklü gelip çoğu kişinin açmadığı uygulamalar (Play Oyunlar, kullanım kılavuzu...) |
| `aggressive` | + özellik kapatanlar: Google Asistan, Chromecast, telefondan kumanda |

Asistan ve Chromecast varsayılan olarak **kapatılmaz**: kumandanın mikrofon tuşu ve telefondan/bilgisayardan yayın çalışmaya devam eder.

### Launcher adımı ne yapar

1. APK'yı yükler ve **önce gerçekten açıldığını doğrular**. Açılmazsa stok launcher'a dokunulmaz.
2. muffuqOS'u varsayılan ana ekran yapar.
3. Stok launcher'ı ve sadece onu besleyen servisleri (öneri kanalları vb.) kapatır, böylece RAM'de yer açılır.
4. Home tuşunun muffuqOS'a gittiğini kontrol eder.

Sistemin açılışta ihtiyaç duyduğu `FallbackHome`'a asla dokunulmaz.

### Geri alma

`backups/<tv-kimliği>.json` dosyası muffuqOS'un kapattığı her paketi ve değiştirdiği her ayarın **orijinal** değerini tutar. `restore` hepsini geri açar, stok launcher'ı tekrar ana ekran yapar ve istersen muffuqOS'u kaldırır.

## Ekran yansıtma

### iPhone / iPad (AirPlay, sesli)

muffuqOS'un içinde bir AirPlay alıcısı var; TV açık olduğu sürece arka planda hazır bekler (açılışta Canlı TV gelse bile).

**Denetim Merkezi → Ekran Yansıtma → muffuqOS**. Görüntü TV'nin donanım çözücüsüyle, ses AAC-ELD olarak gelir. Bitirmek için iPhone'dan durdur ya da TV'de **Geri**. Başka bir cihaz bağlanırsa o devralır.

**Ses:** iPhone'un ses tuşları TV'nin kendi sesini açıp kısar (TV'nin ses çubuğu çıkar); bir basış TV'de 3 adım (`TvVolume.STEPS_PER_PRESS`). iPhone'un ses çubuğu TV'nin o anki seviyesinden başlar; çubuğun tamamı 48 adım, daha yükseği kumandayla. Arada kumandayla oynarsan iPhone oradan devam eder, sese zıplatmaz. En alta indirmek TV'yi sessize alır.

Protokol [UxPlay](https://github.com/FDH2/UxPlay) ile aynı: özellik biti 27 kapalı olduğu için iOS eşleştirme yapmadan, hızlı bağlanır. FairPlay anahtar çözümü (`launcher/app/src/main/cpp/playfair`) UxPlay'den değiştirilmeden alındı.

**Sadece ses de olur:** Spotify, Apple Müzik vb.'de AirPlay ile hoparlör olarak **muffuqOS**'u seç; ses (ALAC) arka planda çalar, ekrandaki uygulama kesilmez. ALAC çözücü Apple'ın açık kaynak kodu (`cpp/alac`, Apache-2.0).

Şimdilik yok: H.265/4K yansıtma, PIN ile erişim kontrolü.

### Bilgisayar (Sunshine + Moonlight)

- PC'de **Sunshine** (servis olarak, açılışta başlar) — web paneli `https://localhost:47990`.
- TV'de **Moonlight** — launcher'daki **Yansıt** düğmesinden ya da uygulamalardan aç → bilgisayar → **Desktop**.
- Görüntü ekran kartında (NVENC) kodlanır, ses TV'ye gider (PC hoparlörü o sırada susar). Çıkmak için Moonlight'ta Geri.

### Android telefon

TV'deki Chromecast (dokunulmadı): uygulamalardaki yayın simgesi.

## Sahne (şarkı sözleri + ışık)

TV'deki Spotify'da ne çalıyorsa (telefondan ya da bilgisayardan Spotify Connect ile yönetilse bile) sözlerini kelime kelime animasyonla, ekran kenarında ritme göre atan bir ışıkla gösterir. Verci ve Lumn'dan esinlenildi.

- **Açmak:** launcher'ın üst barındaki **♪** düğmesi (müzik çalarken şarkının adını gösterir).
- **Kumanda:** ◀ ▶ sahne değiştir · OK durdur/oynat · ⏮ ⏭ şarkı · Geri çıkış.
- **Sahneler:** **Otomatik** (şarkıyı bölümlere ayırıp sahneler arasında kesme yapar, nakaratlara en çarpıcıları verir) · Ship · Fisheye · Visual · Fisheye Visual (Verci tarzı kelime yığını) · Dalga · Tünel · Dev · Poster.
- **Renkler** albüm kapağından gelir; arka plan bulanık kapaktır. Kelimeler ekrana çarptığında oradan renk dalgası yayılır ve palet değişir.
- **Ritim:** TV'nin ses çıkışı Android'in görselleştiricisiyle dinlenir (mikrofon kullanılmaz). Bu yüzden `RECORD_AUDIO` izni var.
- **Sözler:** NetEase (çoğu yabancı şarkıda gerçek kelime zamanlaması) ve LRCLIB (satır zamanlaması; Türkçe için iyi). Satır zamanlamalı sözlerde kelimeler tahminle parlar. Bulunan sözler TV'de önbelleğe alınır.
- **İzinler:** launcher adımı Spotify'ın çalan şarkısını görmek için bildirim dinleyiciyi açar (`cmd notification allow_listener`); **Geri al** kapatır.

Bilinen: bu TV'nin yazılımı, launcher çalışmıyorken (ör. güncellemeden hemen sonra) Home'a basılınca müziği duraklatır. Launcher bunu fark edip müziği devam ettirir.

## Ekran koruyucu

muffuqOS'un kendi ekran koruyucusu: büyük saat, yavaşça süzülen renkli ışık; saat panelde iz bırakmasın diye dakikalar içinde yer değiştirir. Launcher adımı onu seçer, **Geri al** öncekini (ör. Google ortam modu) geri getirir. TV Ayarlar → Ekran koruyucu'dan da değiştirilebilir.

## Launcher

- **Kumandayla:** OK aç · OK'yi **basılı tut** seçenekler (favori, sırala, gizle, uygulama bilgisi, kaldır) · Geri başa dön · Home başa dön
- **Üst bar:** Sahne (♪), Yansıt, Ağ, Ayarlar, Özelleştir (vurgu rengi, canlı arka plan, gizli uygulamalar)
- Telefon tipi uygulamalar sadece **senin yüklediklerin** için gösterilir (yan yüklenen uygulamalar görünsün diye); firmware'in telefon tipi bileşenleri listeyi kirletmez.
- **Canlı arka plan** zayıf TV'lerde Özelleştir panelinden kapatılabilir.

Bellek: 1 GB'lık bir Skyworth/Beko TV'de ~40-65 MB (stok Android TV launcher'ı + öneri servisleriyle aynı ya da daha az). Bu yüzden blur kullanılmaz; parlama ve arka plan katmansız çizilir.

### Derleme

Android Studio kurulu olmalı (JDK ve SDK 36 için).

```powershell
cd launcher
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat dist          # -> dist\muffuqOS-launcher.apk
```

Release APK bu bilgisayarın debug anahtarıyla imzalanır. Güncellemeleri aynı bilgisayardan kur; başka bilgisayarda derlersen önce `restore -UninstallLauncher`.

## Geliştirici araçları

`tools/dev/airplay_sender.py` — iPhone olmadan AirPlay alıcısını test eden sahte gönderici (gerçek protokol, şifreli H.264 + AAC-ELD). FairPlay'in istemci tarafı Apple'a özel olduğu için oturum anahtarını **debug** sürümün logcat çıktısından okur; release sürümde `--no-key` ile sadece protokol adımlarını sınar.

```powershell
pip install cryptography
python tools\dev\airplay_sender.py --tv 192.168.1.50 --serial 192.168.1.50:5555 --video test.h264 --seconds 15
# dikey -> yatay dönüş:  --size 720 1280 --rotate-to yatay.h264 --rotate-size 1280 720
# ses (debug'da: adb shell am broadcast -n com.muffuq.os/.debug.EldTestEncoder):  --audio eld.bin --spf 512 --mute
# ALAC (Müzik uygulaması gibi):  --audio alac.bin --ct 2 --spf 4096 --mute   (alac.bin: [2 bayt uzunluk][ALAC karesi]...)
```

## Lisans notu

`launcher/app/src/main/cpp/playfair` GPL-3.0 (UxPlay/playfair). muffuqOS'u başkalarıyla paylaşacaksan kaynak kodunu da GPL-3.0 ile açman gerekir.

## Dosya yapısı

```
muffuq.bat                 araç kutusunu açar
tools/muffuq.ps1           giriş noktası, menü
tools/lib/*.ps1            Adb, State (yedek), Speed, Launcher, Restore, Status, Ui
debloat/packages.txt       paket listesi
launcher/                  Android projesi (ui/, data/, airplay/, stage/, dream/, cpp/playfair, cpp/alac)
tools/dev/                 geliştirici araçları (AirPlay test göndericisi)
dist/                      derlenmiş APK (+ dist/apps/ Moonlight)
backups/                   TV başına değişiklik kaydı (silme!)
```
