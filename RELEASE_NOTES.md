# yaver 5.1 (Mika'dan esinlenen özellikler)

## Durdurma, araya girme, sıra
- **Durdurunca özet:** Durdur'a basınca yaver ne yaptığını (gönderilen veya taslağa kaydedilen e-postalar, yazılan dosyalar, oluşturulan etkinlikler), ne bulduğunu ve neyin kaldığını kısaca yazar. "Devam" dediğinde kaldığı yerden sürer.
- **İş sürerken yazarken iki seçenek:**
  - "Şimdi ekle": mesaj mevcut işe eklenir.
  - "Sıraya al": mesaj bu iş bitince sıradaki iş olarak başlar.

  Mesajın altında durumu görünür: "Alındı", "Göreve eklendi", "Sonraki iş olarak sıraya alındı".
- **Geri al / Düzenle:** henüz modele iletilmemiş bir mesaja uzun basınca geri alabilir ya da düzenleyebilirsin.
- **Kesilen cevap saklanır:** araya girdiğin anda ekranda görünen yarım cevap, "kesildi" etiketiyle sohbette kalır; model senin gördüğünü bilir.
- **Sıradaki işler kendiliğinden başlamaz** şu iki durumda:
  - uygulama yeniden açıldığında sırada iş varsa,
  - Durdur'dan sonra.

  Bu durumlarda "Sıradakileri başlat / Temizle" çıkar.

## Cevabın altında
- **Kaynaklar:** cevabın gerçekten dayandığı web sayfası, e-posta ve dosyalar en çok 6 tane olarak listelenir. Bu listeyi model değil sistem çıkarır, uydurulamaz. Dokununca açılır.
- **İş özeti:** "12 sn · 4 model çağrısı · 7 araç · 18k token". Dokununca ayrıntı ve işin sonucu (tamamlandı / kısmen / yapılamadı) görünür.
- **Token kullanımı sayfası:** Ayarlar'da; güne ve modele göre.

## Başarıdan öğrenme
- **Arka planda değerlendirme:** her araçlı iş bitince arka planda "tamamlandı mı" diye değerlendirilir. Sadece gerçekten araç kullanan işlerde ve öğrenme açıkken yapılır; tek bir hafif model çağrısıdır.
- **Beceri taslağı:** birkaç adımlı ve başarılı bir iş, beceri taslağı olarak kaydedilir: "Bu işi beceri olarak kaydettim (deneme) — Göster / Tut / Sil".
  - Sen "Tut" demeden yaver bu beceriyi kullanmaz.
  - Aynı türden bir iş gelirse yeni bir beceri açılmaz, mevcut olan güncellenir.
  - Onaylanmış bir beceri üst üste başarısız olursa kapanır ya da önceki sürümüne döner.
- **Önceki işlerden ipucu:** benzer bir iş daha önce başarıyla yapıldıysa, adımları modele ipucu olarak verilir. Hiçbir adım otomatik tekrarlanmaz.
- **Taslaklarda güvenlik:** şifre, kod, kart numarası, kişisel bilgi ya da e-postadan kopyalanmış metin içeren taslaklar atılır.

## Zamanlanmış ve koşullu işler
- **Üç tür:**
  - **Hatırlatma:** modeli çağırmadan, bildirimle ve sohbete mesajla gelir.
  - **Görev:** zamanı gelince yaver çalışır.
  - **Koşullu görev:** "dolar 35'i geçerse", "50'den fazla düşerse", "her yağmurda", "en fazla günde bir" gibi.
- Koşul kontrolü yalnızca okuma yapar. Kontrol hata verirse bu "koşul sağlanmadı" sayılmaz; değer bir önceki kontrolle karşılaştırılır.
- Sonuç her zaman işi oluşturduğun sohbete gelir. Aynı zamanlama asla iki kez çalışmaz. Uygulama kapanırsa sonuç "bilinmiyor, tekrarlanmadı" olarak işaretlenir.
- **"Zamanlanmış işler" sayfası:** Etkin ve Duraklatıldı bölümleri, son sonuç, duraklat/sürdür, şimdi çalıştır, zamanı değiştir, sil.
- Eski hatırlatmalar yeni sisteme kayıpsız taşınır.

## Güvenlik ve hafiza
- **Dış metin işaretlenir:** web, e-posta, ek, belge, arama ve alt ajan çıktıları hep "güvenilmez" olarak işaretlenir. Metnin içine yazılmış sahte etiketler etkisiz kalır.
- **Hafıza:** yaver hafızasına yalnızca senin söylediğini yazar; bir e-postadan ya da sayfadan gelen bilgiyi, açıkça "hatırla / aklında tut" demedikçe yazmaz. Şifre, doğrulama kodu, IBAN ve kart numarası, TC kimlik no ve API anahtarı engellenir. "Nasıl yapılır" türü notlar senin onayını bekler ("Onay bekleyen" bölümünde).
- **Sohbet mi, iş mi:** zayıf modellerde "merhaba nasılsın" gibi sohbet mesajlarında gereksiz araç kullanımı engellenir.

# yaver 5.0.4: widget takvimi (2)
- **Haftanın ilk toplantısı:** bugün ve yarın boşsa (akşam, cuma günü) widget artık 7 gün içindeki ilk toplantıyı gösteriyor, ör. "Pazartesi · 09:30–10:00". Eskiden yalnızca bugünün kalanı ve yarın gösteriliyordu.
- **"Alınamadı" ile "toplantı yok" ayrıldı:** takvim alınamadıysa widget "Takvim alınamadı · dokunun" yazıyor. Dokununca takvim açılıyor ve widget o veriyle hemen düzeliyor.
- **Takvim ekranı her görünümde widget'ı besliyor:** gün, hafta ve ay görünümünün hepsi. Eskiden gün görünümü ve varsayılan başlangıç saati yüzünden bu veri reddediliyordu.
- **Widget çizilemezse:** daha basit bir biçimle yeniden deneniyor; hata tanı günlüğüne yazılıyor.
- **"Posta bağlantısını sına" testine "Bugün kartı ve widget" adımı eklendi:** kaç etkinlik bulunduğunu, nereden geldiğini ve widget'ın çizilip çizilmediğini söylüyor.

# yaver 5.0.3: widget takvimi
- **Asıl hata:** posta oturumu düşünce arka plandaki yenileme takvimi alamıyor, elindeki Exchange toplantılarını telefon takvimiyle (çoğu zaman boş) değiştiriyordu. Widget "toplantı yok" ya da eski bir görüntüyle kalıyordu. Artık alınamayan takvim silinmiyor; bilinen toplantılar, bugünü kapsadıkları sürece gösterilmeye devam ediyor.
- **Uygulamadaki değişiklikler hemen yansıyor:** toplantı ya da randevu oluşturma, değiştirme, kabul/ret, iptal ve silme birkaç saniye içinde widget'a geçiyor.
- **Takvim ekranı widget'ı da tazeliyor:** uygulamada takvime bakınca widget o güncel veriyi hemen alıyor.
- **Ekranı açınca widget tazeleniyor:** biten toplantı düşüyor, saatler güncelleniyor, veri eskiyse arka planda yenileniyor.
- **Toplantı geçişi zamanında:** toplantı başlayınca ya da bitince geçiş artık dakikasında oluyor; eskiden telefon kendini beklettiği kadar gecikebiliyordu.

# yaver 5.0.2
- **Yanıt, iletme ve toplantı yanıtı düzeldi:** "ChangeKey is required for this operation" hatası artık çıkmıyor. Exchange bu işlemlerde e-postanın sürüm anahtarını istiyordu ama yaver göndermiyordu. Artık bütün posta işlemlerinden önce bu anahtar otomatik ekleniyor. Sunucu başka bir işlemde de isterse anahtar eklenip bir kez daha deneniyor.
- **Posta işlemleri test edildi:** yeni posta, yanıt, tümünü yanıtla, iletme, taslak kaydetme ve gönderme, toplantı daveti, randevu, tüm gün etkinliği, kabul/belki/ret, toplantı güncelleme, toplantı iptali, randevu silme, posta silme ve kalıcı silme sahte sunucuya karşı denendi (26 kontrol).
- **"Posta bağlantısını sına" testine yeni adımlar:** gerçek sunucunda yanıt taslağı, iletme taslağı (hiçbir şey gönderilmez, hemen silinir) ve yarın 23:00'e bir test randevusu oluşturup silme.
- **Widget yeniden tasarlandı:**
  - "y" rozeti ve tarih
  - okunmamış, yanıt bekleyen ve alarm için küçük etiketler
  - sıradaki toplantı kartı: saat aralığı, "20 dk sonra", yer ya da çevrim içi simgesi
  - "yaver'e sor" çubuğu ve renkli mikrofon düğmesi
  - açık ve koyu temaya uyum
  - kısa boyda toplantı tek satıra iner
  - widget seçicide önizleme resmi

# yaver 5.0.1
- Posta bağlıyken "Bugün" kartında "Posta bağlı değil" yazması düzeltildi. Uygulama açılışında, posta daha bağlanmadan yapılan ilk denemenin "bağlı değil" sonucu 5 dakika boyunca önbellekte kalıyordu. Kart artık o anki gerçek durumu gösteriyor. Posta bağlanınca kart ve widget hemen tazeleniyor.

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
