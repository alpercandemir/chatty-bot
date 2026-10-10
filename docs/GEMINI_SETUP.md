# Gemini seçimi ve yerel key ayarları

**Durum:** Java SDK adapter, lokal `.env` yükleme ve Docker Compose bağlantıları implemente edildi. Canlı model erişiminin doğrulanması `scripts/live-smoke.py` ile yapılır; taşıma hatasında model/key doğrulanmış sayılmaz.

## Sağlayıcı kararı

- **Firma / API:** Google, Gemini Developer API; Google AI Studio projesi üzerinden.
- **Başlangıç modeli:** `gemini-3.8-flash`; Google'ın model sayfasında stable ve function calling destekli olarak listeleniyor. [Model belgesi](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash)
- **Hedef:** Free Tier proje ile sağlayıcı maliyeti olmadan prototip gösterimi. Google'ın 10 Ekim 2026'da kontrol edilen fiyat tablosunda bu modelin Standard giriş/çıkış tokenları Free Tier'da ücretsiz. [Fiyatlandırma](https://ai.google.dev/gemini-api/docs/pricing)
- **Java istemcisi:** resmi Google GenAI Java SDK, `com.google.genai:google-genai`; sürüm `1.76.0` olarak sabitlendi. [SDK belgesi](https://ai.google.dev/gemini-api/docs/libraries)

Free Tier sınırsız kullanım anlamına gelmez. Güncel proje/model kotaları AI Studio'dan kontrol edilir; uygulama ayarı bir Google projesinin faturalandırma katmanını değiştiremez. Kullanılacak key Free Tier projesinden gelmelidir. Projede billing açılması veya ücretli hizmete geçiş bu prototipin kapsamı değildir. [Kota belgesi](https://ai.google.dev/gemini-api/docs/rate-limits)

## Key'i projeye ekleme

Proje kökünde aşağıdaki kopyalamayı yap:

```sh
cp .env.example .env
```

Oluşan `.env` dosyasındaki boş `GEMINI_API_KEY=` satırına key'i **yerel editöründe** ekle. Sohbete veya dokümana yapıştırmana gerek yok. `.gitignore` bu dosyayı dışlar; `.env.example` boş key alanıyla paylaşılabilir.

| Ayar | Başlangıç değeri | Planlanan davranış |
| --- | --- | --- |
| `AI_PROVIDER` | `gemini` | Gemini adapter'ını seçer |
| `AI_MODEL` | `gemini-3.8-flash` | Model kimliğini belirler; erişim başarısızsa otomatik değiştirilmez |
| `AI_MODE` | `auto` | Key varsa yeni konuşma Live AI, yoksa scripted başlar; `scripted` ve `live` açıkça seçilebilir |
| `GEMINI_API_KEY` | Boş | Kullanıcının yerel key'i; yalnızca Java backend'e iletilir |
| `AI_LIVE_CONCURRENCY` | `1` | Canlı run'ları sıraya koyarak paylaşılan kotayı gözetir |
| `AI_MODEL_MAX_ROUNDS` | `6` | Bir run içindeki model isteklerini sınırlar |

Google istemcileri `GEMINI_API_KEY` veya `GOOGLE_API_KEY` kullanabilir; ikisi tanımlıysa varsayılan davranışta `GOOGLE_API_KEY` önceliklidir. Bizim adapter'ımız proje için seçilen `GEMINI_API_KEY` değerini açıkça kullanacak. [Resmi key belgesi](https://ai.google.dev/gemini-api/docs/api-key)

`.env` Java tarafından kendiliğinden okunmaz. Implementasyonda Compose/environment yüklemesi açıkça yapılacak ve key yalnızca backend'e aktarılacak. Key browser/Vite ayarına, frontend bundle'ına, loglara veya flow eventlerine eklenmeyecek. Yukarıdaki `AI_*` isimleri Chatty ayarlarıdır; Google'ın API parametreleri veya Google kota ayarları değildir.

## Canlı demo ve kota davranışı

1. Scripted modunda key gerekmez; tüm dummy iş sistemleri ve kalıcı değişiklikler çalışır.
2. Live modunda key/model erişimi kontrol edilir; müşteri mesajları ve araç sonuçları Gemini'ye gider.
3. Eksik/yanlış key, erişilemeyen model ve kota dolması anlaşılır hata durumları üretir. Key içeriği hiçbir çıktıda gösterilmez.
4. Kota sınırlaması konuşma veya onaylı business operation'ı sıfırlamaz; ücretli model/sağlayıcı fallback'i çalışmaz.
5. Presenter rehearsal modunu açıkça seçebilir; canlı AI kullanıldığı izlenimi verilmez.
6. Canlı değerlendirme seti ücretsiz kotaya sığan partiler halinde çalıştırılır; gerekiyorsa sonraki kota dönemlerinde tamamlanır.

Ücretsiz katmanın veri kullanım koşulları nedeniyle canlı prototip yalnızca sağlanan sentetik verileri kullanır. [Google'ın Free/Paid karşılaştırması](https://ai.google.dev/gemini-api/docs/pricing)
