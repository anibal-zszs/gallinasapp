const { onDocumentUpdated } = require("firebase-functions/v2/firestore");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();

/**
 * Se ejecuta cuando el dispositivo de conteo actualiza `lastScanAt`.
 * Si scannedHens != expectedHens:
 *   - guarda un registro en history ("Faltan 2 gallinas")
 *   - envía una notificación (alarma) a todos los miembros del gallinero
 */
exports.onScan = onDocumentUpdated("coops/{coopId}", async (event) => {
  const before = event.data.before.data();
  const after = event.data.after.data();

  // Solo reaccionar a un escaneo nuevo
  const prev = before.lastScanAt ? before.lastScanAt.toMillis() : 0;
  const curr = after.lastScanAt ? after.lastScanAt.toMillis() : 0;
  if (curr === prev) return;

  const expected = after.expectedHens || 0;
  const scanned = after.scannedHens || 0;
  if (expected === 0 || scanned === expected) return;

  const diff = expected - scanned;
  const missing = Math.max(diff, 0);
  const message =
    diff > 0
      ? `Faltan ${diff} gallina${diff === 1 ? "" : "s"}`
      : `Sobran ${-diff} gallina${diff === -1 ? "" : "s"}`;

  const db = getFirestore();
  await db
    .collection("coops").doc(event.params.coopId)
    .collection("history")
    .add({ message, missing, scanned, expected, createdAt: FieldValue.serverTimestamp() });

  // Reunir tokens de todos los miembros
  const tokens = [];
  for (const uid of after.members || []) {
    const u = await db.collection("users").doc(uid).get();
    tokens.push(...(u.get("fcmTokens") || []));
  }
  if (tokens.length === 0) return;

  // Mensaje "data" + prioridad alta: la app muestra la notificación con sonido de alarma
  await getMessaging().sendEachForMulticast({
    tokens: [...new Set(tokens)],
    data: { title: `🐔 ${after.name || "Gallinero"}`, body: message },
    android: { priority: "high" },
  });
});
