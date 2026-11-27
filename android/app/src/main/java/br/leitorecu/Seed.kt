package br.leitorecu

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser

/**
 * Dados aprendidos no carro (velocidade da central, marchas, mapas, ar-condicionado, saúde do motor, Painel...),
 * copiados do celular e embutidos no APK em assets/seed/<arquivo>.xml (mesmo formato das preferências do Android).
 *
 * Na primeira abertura depois de uma instalação NOVA (ex.: desinstalou e instalou de novo), cada arquivo vazio
 * recebe esses dados, e o app não precisa aprender tudo de novo. Numa atualização normal, os dados que já estão
 * no celular são mais novos e são mantidos (nada é sobrescrito).
 */
object Seed {
    private const val FLAG = "applied_v1"

    fun apply(ctx: Context) {
        val flag = ctx.getSharedPreferences("seed", 0)
        if (flag.getBoolean(FLAG, false)) return
        val files = try { ctx.assets.list("seed") ?: emptyArray() } catch (_: Exception) { emptyArray() }
        for (f in files) {
            if (!f.endsWith(".xml")) continue
            val prefs = ctx.getSharedPreferences(f.removeSuffix(".xml"), 0)
            if (prefs.all.isNotEmpty()) continue                 // já tem dados (atualização): mantém os do celular
            try {
                ctx.assets.open("seed/$f").use { input ->
                    val e = prefs.edit()
                    val p = Xml.newPullParser()
                    p.setInput(input, "utf-8")
                    while (p.next() != XmlPullParser.END_DOCUMENT) {
                        if (p.eventType != XmlPullParser.START_TAG) continue
                        val name = p.getAttributeValue(null, "name") ?: continue
                        val v = p.getAttributeValue(null, "value")
                        when (p.name) {
                            "string" -> e.putString(name, p.nextText())
                            "int" -> v?.toIntOrNull()?.let { e.putInt(name, it) }
                            "long" -> v?.toLongOrNull()?.let { e.putLong(name, it) }
                            "float" -> v?.toFloatOrNull()?.let { e.putFloat(name, it) }
                            "boolean" -> v?.let { e.putBoolean(name, it == "true") }
                        }
                    }
                    e.commit()
                }
            } catch (_: Exception) {
                // arquivo de semente com problema: segue sem ele (o app aprende de novo)
            }
        }
        flag.edit().putBoolean(FLAG, true).commit()
    }
}
