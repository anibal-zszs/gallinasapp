package com.inacap.gallinasapp

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

data class Coop(
    val id: String = "",
    val name: String = "",
    val code: String = "",
    val ownerId: String = "",
    val expectedHens: Int = 0,
    val scannedHens: Int = 0,
    val doorState: String = "closed",     // "open" | "closed" | "moving"
    val doorCommand: String = "",          // "open" | "close" | "" (lo escribe la app)
    val scanRequest: Boolean = false,      // la app pide un escaneo
    val lastScanAt: Timestamp? = null
)

data class HistoryItem(
    val id: String = "",
    val message: String = "",
    val missing: Int = 0,
    val createdAt: Timestamp? = null
)

object CoopRepository {
    private val db = FirebaseFirestore.getInstance()
    private val uid get() = AuthRepository.currentUser?.uid ?: error("Sin sesión")

    /** Devuelve el id del gallinero del usuario (o null si aún no tiene). */
    suspend fun myCoopId(): String? =
        db.collection("users").document(uid).get().await().getString("coopId")

    suspend fun createCoop(name: String): String {
        val code = generateCode()
        val ref = db.collection("coops").document()
        ref.set(
            mapOf(
                "name" to name.trim(),
                "code" to code,
                "ownerId" to uid,
                "members" to listOf(uid),
                "expectedHens" to 0,
                "scannedHens" to 0,
                "doorState" to "closed",
                "doorCommand" to "",
                "scanRequest" to false
            )
        ).await()
        db.collection("users").document(uid).set(mapOf("coopId" to ref.id), com.google.firebase.firestore.SetOptions.merge()).await()
        return ref.id
    }

    suspend fun joinCoop(code: String): Result<String> = runCatching {
        val snap = db.collection("coops")
            .whereEqualTo("code", code.trim().uppercase())
            .limit(1).get().await()
        val doc = snap.documents.firstOrNull() ?: error("Código no válido")
        doc.reference.update("members", FieldValue.arrayUnion(uid)).await()
        db.collection("users").document(uid).set(mapOf("coopId" to doc.id), com.google.firebase.firestore.SetOptions.merge()).await()
        doc.id
    }

    fun observeCoop(coopId: String): Flow<Coop?> = callbackFlow {
        val reg = db.collection("coops").document(coopId).addSnapshotListener { s, _ ->
            trySend(s?.toObject(Coop::class.java)?.copy(id = s.id))
        }
        awaitClose { reg.remove() }
    }

    fun observeHistory(coopId: String): Flow<List<HistoryItem>> = callbackFlow {
        val reg = db.collection("coops").document(coopId).collection("history")
            .orderBy("createdAt", Query.Direction.DESCENDING).limit(100)
            .addSnapshotListener { s, _ ->
                trySend(s?.documents?.map { it.toObject(HistoryItem::class.java)!!.copy(id = it.id) } ?: emptyList())
            }
        awaitClose { reg.remove() }
    }

    suspend fun setExpectedHens(coopId: String, n: Int) {
        db.collection("coops").document(coopId).update("expectedHens", n).await()
    }

    /** El dispositivo (ESP32, etc.) lee doorCommand, mueve la puerta y actualiza doorState. */
    suspend fun sendDoorCommand(coopId: String, command: String) {
        db.collection("coops").document(coopId)
            .update(mapOf("doorCommand" to command, "doorState" to "moving")).await()
    }

    /** El dispositivo de conteo ve scanRequest=true, cuenta, y escribe scannedHens + lastScanAt. */
    suspend fun requestScan(coopId: String) {
        db.collection("coops").document(coopId).update("scanRequest", true).await()
    }

    /** Guarda el token FCM para recibir alertas en este teléfono. */
    suspend fun saveFcmToken() {
        val token = FirebaseMessaging.getInstance().token.await()
        db.collection("users").document(uid)
            .set(mapOf("fcmTokens" to FieldValue.arrayUnion(token)), com.google.firebase.firestore.SetOptions.merge())
            .await()
    }

    private fun generateCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars.random() }.joinToString("")
    }
}
