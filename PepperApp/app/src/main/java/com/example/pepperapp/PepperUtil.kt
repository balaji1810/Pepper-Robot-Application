package com.example.pepperapp

import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.SayBuilder
import com.aldebaran.qi.sdk.`object`.conversation.Phrase
import com.aldebaran.qi.sdk.`object`.locale.Language
import com.aldebaran.qi.sdk.`object`.locale.Locale
import com.aldebaran.qi.sdk.`object`.locale.Region
import kotlinx.coroutines.*

object PepperUtil {
    suspend fun sayPhrase(phrase: String, qiContext: QiContext?) {
        if (qiContext == null) return

        withContext(Dispatchers.Main) {
            val phraseObject = Phrase(phrase)

            suspendCancellableCoroutine<Unit> { continuation ->
                CoroutineScope(Dispatchers.Default).launch {
                    val say = SayBuilder.with(qiContext)
                        .withLocale(Locale(Language.ENGLISH, Region.ITALY))
                        .withPhrase(phraseObject)
                        .build()

                    withContext(Dispatchers.Main) {
                        say.async().run()
//                            .thenConsume {
//                            continuation.resume(Unit)
//                            sendPhraseToServer(phrase)
//                        }
                    }
                }
            }
        }
    }
}