package com.sample.videohub.rtm

import android.util.Log
import org.json.JSONObject

enum class EventMessage(val title:String){
    VIDEOGROUP("videoGroup"),
    CALL("call"),
    PK("pk")
}

object MessageDispatcher {

    private val listeners =
        mutableMapOf<String, MutableList<(JSONObject) -> Unit>>()

    private val messageStore =
        mutableMapOf<String, MutableMap<String, JSONObject>>()

    fun on(title: String, listener: (JSONObject) -> Unit) {
        listeners.getOrPut(title) { mutableListOf() }
            .add(listener)
    }

    fun emit(rawJson: JSONObject,
             uniqueKeyField: String = "roomId"
    ) {

        try {

            // message is inside payload and is itself a JSON string
            val messageString = rawJson
                .getString("message")

            val message = JSONObject(messageString)

            val title = message.optString("title")
            val state = message.optString("state")

            if (title.isBlank() || state.isBlank()) {
                return
            }

            val uniqueKey = message.optString(uniqueKeyField)

            if (uniqueKey.isNotBlank()) {

                val store =
                    messageStore.getOrPut(title) {
                        mutableMapOf()
                    }

                when (state) {

                    "add", "update" -> {
                        store[uniqueKey] = message
                    }

                    "remove" -> {
                        Log.e("MessageDisp", "remove: $uniqueKey")
                        store.remove(uniqueKey)
                    }
                }
            }

            // Notify listeners for this title
            listeners[title]?.forEach {
                it.invoke(message)
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    fun getMessages(title: String): List<JSONObject> {
        return messageStore[title]
            ?.values
            ?.toList()
            ?: emptyList()
    }

    fun getMessage(
        title: String,
        key: String
    ): JSONObject? {
        return messageStore[title]?.get(key)
    }


}
