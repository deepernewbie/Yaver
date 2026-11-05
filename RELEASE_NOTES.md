# yaver 5.0

## Yeni
- **"Bugün" kartı:** yeni sohbet ekranında şunlar toplu görünür. Kart katlanabilir ve 5 dakikada bir tazelenir.
  - sıradaki toplantılar (dokununca açılır)
  - yanıt bekleyen e-postalar (dokununca açılır)
  - okunmamış sayısı
  - vadesi gelen görevler
  - sıradaki alarm
  - tek dokunuşla "Günü özetle"
- **Ana ekran widget'ı:** sıradaki toplantı, okunmamış ve yanıt bekleyen sayısı, "Sor" düğmesi (klavye açık gelir) ve mikrofon. Satırlara dokununca posta ya da takvim açılır.
- **Ekrandakini sor:** yaver varsayılan dijital asistan yapılınca, ana tuşa uzun basıldığında o an ekranda olanın metni ve ekran görüntüsü alınır. Sohbet "Ekran: <uygulama>" etiketiyle açılır, "Bu ne diyor?" gibi sorabilirsiniz.
- **Yarıda kalan işe devam:** uygulama bir iş sırasında kapanırsa sohbet açıldığında "Önceki iş yarıda kaldı — Devam et" çıkar.
- **Mesaja uzun basma:** kopyala, paylaş, sesli oku, tekrar sor, düzenleyip gönder.
- **Hareketler:** soldan kaydırınca sohbet listesi açılır. Posta ve takvim aşağı çekilerek yenilenir.
- **Paylaşımlar:** Chrome'dan paylaşılan başlık ve adres artık iki kez yazılmaz. Birden çok metin ve dosya, ayrıca bazı uygulamaların "clip" ile verdiği dosyalar da alınır.

## Daha hızlı ve sağlam ajan
- **Paralel okuma:** bir adımdaki salt okunur araç çağrıları (dosya ya da posta okuma, arama, web'den çekme) 4'e kadar aynı anda çalışır. Değişiklik yapan araçlar yine sırayla çalışır.
- **Zayıf ve ücretsiz modellerin hatalarına dayanıklılık:**
  - tekrarlanan çağrı kimlikleri artık kaybolmaz
  - `functions.x` gibi bozuk araç adları ve kod bloğu içine yazılmış argümanlar düzeltilir
  - metin olarak yazılmış `<tool_call>` blokları çalıştırılır
  - toparlama çağrısı da hata verirse iş hata olarak bitmez, kısa bir özet kalır
- **Hata türüne göre davranış:**
  - 429 ya da 5xx hatasında beklenip yeniden denenir (3/8/20/40 sn, sunucunun "Retry-After" süresine uyulur) ve ekranda "model meşgul — N sn sonra yeniden" yazar.
  - 401 ya da 403 hatası yeniden denenmez; sağlayıcının mesajı gösterilir.
- **Durdur düğmesi:** paralel çalışan bütün ağ bağlantılarını keser.
- **Testler:** ajan döngüsü 22 zor senaryoda (115 kontrol) sahte modelle uçtan uca test edildi.

## Arayüz
- 66 küçük dokunma alanı en az 40 piksele büyütüldü.
- Dar ekranda kesilen başlık, tablo, kod düğmesi, takvim araç çubuğu ve posta araç çubuğu düzeltildi.
- **Uzun sohbetler:** son 30 tur gösterilir, "Önceki mesajları göster" ile devamı gelir. 400 mesajlık sohbet 2–4 kat daha hızlı açılıyor; araç adımı başına güncelleme 117 ms'den 19 ms'ye indi.
- 500 e-postalık liste bölümler hâlinde çizilir.

## Güvenlik
- Başka uygulamalar yaver'e komut çalıştıramaz: dışarıdan gelen istek yalnızca mesaj kutusuna yazılır.
- Paylaşım yoluyla yaver'in kendi gizli dosyaları içeri alınamaz.
- Asistan oturumu bir önceki ekranın verisini yeniden kullanmaz.

## Telefonda yapılacaklar
- **Ekrandakini sor için:** Ayarlar → Uygulamalar → Varsayılan uygulamalar → Dijital asistan uygulaması → yaver. Aynı ekranda "Ekrandaki metni kullan" ve "Ekran görüntüsünü kullan" seçeneklerini açın. İlk açılışta istenen mikrofon iznini verin, böylece diğer uygulamalarda sesle yazma çalışmaya devam eder.
- **Widget için:** ana ekrana uzun basın → Widget'lar → yaver.

# yaver 4.2

- **Cevaplardaki e-postalar dokunulabilir bağlantı:** model e-postayı kısa kimliğiyle yazarsa (`m3k9x2ab`, "(id: …)" ya da kod içinde), kimlik yerine e-postanın konusu ve göndereni gösterilir. Dokununca e-posta açılır. Cümlede konu zaten geçiyorsa bağlantı yalnızca "E-postayı aç" der. Yalnızca gerçek e-posta kimlikleri bağlantıya dönüşür; "mesajlar" gibi kelimelere dokunulmaz.
- Modele de e-postaları kimlik yerine `[Konu — Gönderen](mail:…)` bağlantısı olarak yazması söylendi.
- **Cevaplardaki dosyalar da dokunulabilir bağlantı:** `/workspace/...` yolları, `ornek-rapor.docx` gibi yalın dosya adları, `uploads/x.m4a` gibi göreli yollar ve kod içinde yazılan adlar (boşluklu olanlar dahil) dosya simgesi ve adıyla gösterilir. Dokununca önizleme açılır. Yalın ad, dosya çalışma alanında gerçekten varsa bağlantıya dönüşür (alt klasörlerde de aranır). Dosyaya verilen Markdown bağlantıları kendi metinleriyle aynı görünümü alır.

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
