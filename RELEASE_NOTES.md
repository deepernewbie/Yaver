# yaver 4.1: yenilikler (4.0'dan bu yana)

## Ses ve video
- Yeni **media** aracı, telefonun kendi kod çözücüleriyle çalışır; ffmpeg gerekmez. m4a, mp3, aac, ogg, opus, flac, wav, amr ve videoların sesini okur. Sonucu WAV ya da m4a olarak yazar.
- **Tek adımda ses iyileştirme:** "Bu sesi iyileştir" dendiğinde yaver şunları yapar ve sonucu `ad-temiz.m4a` olarak kaydeder:
  - arka plan gürültüsünü azaltır (gürültüyü kaydın sessiz yerlerinden öğrenir),
  - uğultuyu keser,
  - konuşmayı netleştirir,
  - ses seviyesini dengeler.
- Terminalde **ffprobe**, **ffmpeg -i GİRİŞ ÇIKIŞ.wav|m4a** (-ss/-t ile kesme dahil) ve **media enhance** komutları çalışır.
- Dosyalar sayfasında ses ve video dosyaları **uygulama içinde çalınır** ve ileri-geri sarılabilir.

## Sayfa çalıştırıcı ve tarayıcı
- run_html ile çalışan sayfaların indirmeleri artık **/workspace/downloads** klasörüne kaydedilir: tıklanan ya da yalnızca oluşturulan indirme bağlantıları ve `window.yaver.save(...)`. Rapor, kaydedilen dosyaları listeler.
- `eval` bir vaat (promise) döndürürse sonucu beklenir (en çok 60 sn).
- Tarayıcı aracı diskteki dosyaları açabiliyor (`/workspace/sayfa.html`).

## Daha az takılma
- Model bir araç çağrısını bozuk gönderirse (yarıda kesilmiş ya da `{}`), yaver hatayı modele göstermeden önce aynı soruyu bir kez de akışsız sorar.
- Varsayılan adım sınırı 30'dan 40'a çıktı.
- Akıl yürüten ücretsiz modellerin arka plan çağrılarına daha geniş bütçe veriliyor; boş cevap daha az.

## Öğrenme
- Adımları biten ya da döngüye giren işlerden artık **mutlaka ders çıkarılıyor**: neyin engellediği ve hangi yolun işe yaradığı kaydediliyor. Bir sonraki benzer iş birkaç adımda biter.

# yaver 4.0: yenilikler (3.7'den bu yana)

## Sohbet
- **Araç kartları daha ayrıntılı:** her adımın argümanları, süresi, canlı **ilerleme günlüğü** ve 6.000 karaktere kadar sonucu görünür. Oluşturulan dosya, kartın üstünde tek dokunuşla açılır.
- **Değişen dosyalar kartı:** bir turda değişen dosyalar "+eklenen −silinen" sayılarıyla listelenir. Farkı görebilir ya da **tümünü geri alabilirsiniz**.
- **System 1 şeridi:** yaver'in hızlı kararları (yönlendirme, araç seçimi, döngü denetimi) turun altında küçük etiketler olarak görünür.
- Başlıkta **tur ve araç çağrısı sayısı**, yanıtların altında **saat ve yanıt süresi** görünür.
- yaver çalışırken yazdığınız mesaj iletilince **"Çalışırken iletildi"** olarak işaretlenir.
- Yanıtlardaki dosya adları tıklanabilir ve bahsedilen resimler yanıtın altında küçük resim olarak sıralanır.
- **/clear geri alınabilir.** Önceki bağlam katlanır ve "Önceki bağlamı göster" ile açılır.
- Mesaj kutusunda **izin** ve **Önce planla** çipleri, düzenlenebilir **karşılama kısayolları**, resim yapıştırma, eklenen .zip'i açma.
- Başlığa dokunarak **model**, ⋮ menüsünden **System-1** hızlıca değiştirilir.
- **Ajanın tarayıcısını canlı izleyin** (⋮ ya da /browser). Resim ve metin görünümü, adres çubuğu, geri, yenile ve **Devral** düğmeleri vardır.
- yaver çalışırken "y" logosu hareketlenir.
- Fiziksel klavye kısayolları: Enter gönderir, Alt+N yeni sohbet açar, Alt+L bağlamı temizler, Esc geri döner.

## Dosyalar
- Yeni **Dosyalar** sayfası (menüden, /files). İçinde gezinebilir, süzebilir ve sıralayabilirsiniz. Dosya yükleyebilir, klasör ve not oluşturabilirsiniz. Yeniden adlandırma, taşıma, kopyalama ve silme de buradan yapılır (korumalı klasörler silinmez).
- **Uygulama içi önizleme:** Markdown, CSV tablosu, JSON, kod, PDF sayfaları ve Word/Excel/PowerPoint metni. Metin dosyalarını **düzenleyebilirsiniz**.
- HTML sayfaları yaver'in tarayıcısında **açılır** ve **Çalıştır ve denetle** ile hatalar, eksik dosyalar ve taşmalar raporlanır.
- Klasörler telefona **.zip** olarak kaydedilir. Sayfanın altında depolama kullanımı ve belge dizini bilgisi yer alır.

## Belgeler
- yaver gerçek **Word, Excel, PowerPoint ve PDF** dosyaları hazırlar. Hazırlanan dosya yanıtın altında açılabilir bir kart olarak görünür.

## Posta ve takvim
- E-postalar **biçimli (HTML)** görünür. İnternetteki resimler "Resimleri göster / Her zaman göster" ile açılır.
- Listede **yapay zekâ özeti**, "yanıt bekliyor" ve öncelik etiketleri vardır. Tümü, Okunmamış ve Yanıt bekleyen süzgeçleri eklendi.
- **Yerel arama:** tarih, kimden/kime, ek ve klasöre göre süzme. Akıllı (yapay zekâ) arama da eklendi.
- Sunucu klasörleri okunmamış sayılarıyla görünür. **Arşivle, taşı, okunmadı yap, kalıcı sil ve klasörü boşalt** işlemleri yapılabilir. Silmeyi **geri alabilirsiniz**.
- **Taslakları** açıp düzenleyebilir ve gönderebilirsiniz. **Yapay zekâ ile taslak ya da yeniden yazma** eklendi.
- Okuma ekranında **Özetle**, **E-postadan görev** ve **Elle görev** düğmeleri var. Eklerle ilgili yaver'e soru sorabilirsiniz.
- Takvimde **Gündem, Gün, İş haftası, Hafta ve Ay** görünümleri ve ay gezgini bulunur. **Etkinlik oluşturabilir, düzenleyebilir, silebilir ya da iptal edebilirsiniz.**

## Hafıza ve yetenekler
- **Yetenek düzenleyici:** yeni yetenek oluşturma, düzenleme, kopyalama, silme ve dosya ekleme. Yetenekler kullanım sıklığına göre sıralanır.
- MEMORY.md ve USER.md metin olarak düzenlenebilir. Hafıza sınırları artık ayarlanabilir.
- Hazır gelen "web-research" yeteneği eklendi.

## Ayarlar
- **İzin ayarı:** Önce sor / Otomatik / Tam otomatik / Salt okunur (/permissions ile de değişir).
- **Model:** sıcaklık, görev başına en fazla adım, ek istek başlıkları, resim gönderme ve System-1 durum boyutu ayarlanabilir.
- **Model seçici** artık bağlam boyutunu ve ücretsiz modelleri gösterir. "Yalnızca ücretsiz" süzgeci eklendi.
- **Görünüm:** Telefon gibi / Açık / Koyu tema. Arayüz dili artık cevap dilinden ayrı seçilir.
- **Kullanım:** model çağrıları, token sayıları ve amaca göre çağrılar gösterilir. Sayaçlar sıfırlanabilir. System-1 karar günlüğü artık **temizlenebilir**.
- **Yedek:** her şey tek bir .zip olarak dışa aktarılır (istenirse API anahtarlarıyla birlikte). İçe alırken **birleştir** ya da **her şeyi değiştir** seçilebilir; web yaver dışa aktarımları da okunur. **Her şeyi sil** seçeneği eklendi.

## Komutlar ve tanılama
- Yeni komutlar: /permissions, /settings, /mail, /calendar, /tasks, /files, /memory, /browser, /undo, /debug, /help.
- **Hata ayıklama dökümü:** sistem istemi, hafıza, model istekleri ve System-1 kararlarını içerir; anahtarlar çıkarılır. Paylaşılabilir.

## Düzeltmeler
- Plan modu artık dosya düzenleme, komut, kod ve belge oluşturma gibi değişiklikleri onaydan önce engelliyor.
- System-1 seçenekleri Türkçe arayüzde Türkçe görünüyor.
