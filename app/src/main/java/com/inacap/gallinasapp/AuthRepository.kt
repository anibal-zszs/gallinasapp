package com.inacap.gallinasapp

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import kotlinx.coroutines.tasks.await

/**
 * Firebase Auth guarda la sesión en el teléfono automáticamente.
 * Mientras NO llames a signOut(), el usuario sigue dentro aunque cierre la app.
 */
object AuthRepository {
    private val auth = FirebaseAuth.getInstance()

    const val MIN_PASSWORD = 8

    val currentUser get() = auth.currentUser

    /** Sesión válida = hay usuario y ya verificó su correo. */
    fun isLoggedIn(): Boolean = auth.currentUser?.isEmailVerified == true

    suspend fun register(email: String, password: String): Result<Unit> = runCatching {
        require(password.length >= MIN_PASSWORD) { "La contraseña debe tener al menos 8 caracteres" }
        val result = auth.createUserWithEmailAndPassword(email.trim(), password).await()
        // Envía el correo de activación
        result.user?.sendEmailVerification()?.await()
        // Se cierra sesión hasta que active su cuenta
        auth.signOut()
    }.mapError()

    suspend fun login(email: String, password: String): Result<Unit> = runCatching {
        val result = auth.signInWithEmailAndPassword(email.trim(), password).await()
        val user = result.user ?: error("No se pudo iniciar sesión")
        user.reload().await()
        if (!user.isEmailVerified) {
            auth.signOut()
            throw IllegalStateException("Debes activar tu cuenta desde el correo que te enviamos")
        }
    }.mapError()

    suspend fun resendVerification(email: String, password: String): Result<Unit> = runCatching {
        val result = auth.signInWithEmailAndPassword(email.trim(), password).await()
        result.user?.sendEmailVerification()?.await()
        auth.signOut()
    }.mapError()

    suspend fun resetPassword(email: String): Result<Unit> = runCatching {
        auth.sendPasswordResetEmail(email.trim()).await()
        Unit
    }.mapError()

    fun logout() = auth.signOut()

    private fun <T> Result<T>.mapError(): Result<T> = recoverCatching { e ->
        throw Exception(
            when (e) {
                is FirebaseAuthUserCollisionException -> "Ese correo ya está registrado"
                is FirebaseAuthInvalidCredentialsException -> "Correo o contraseña incorrectos"
                is FirebaseAuthInvalidUserException -> "Correo o contraseña incorrectos"
                is IllegalArgumentException, is IllegalStateException -> e.message ?: "Error"
                else -> e.message ?: "Error desconocido"
            }
        )
    }
}
