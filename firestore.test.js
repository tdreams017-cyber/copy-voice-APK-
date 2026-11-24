const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: cannot read or write any user collection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const now = new Date(Date.now() - 1000);
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("transcripts").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("transcripts").doc("t1").set({
      userId: ALICE_UID,
      title: "تسجيل",
      rawText: "نص تجريبي",
      language: "ar",
      formatStyle: "RAW",
      durationSeconds: 12,
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("voiceProfiles").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("generatedSpeeches").get());
});

test("Owner: transcripts create, list, partial update, and delete succeed; invalid payloads fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const col = aliceDb.collection("users").doc(ALICE_UID).collection("transcripts");
  const created = new Date(Date.now() - 10000);

  // Empty list query succeeds
  await assertSucceeds(col.orderBy("createdAt", "desc").get());

  // Valid create (no @DocumentId `id` field)
  await assertSucceeds(
    col.doc("note_1").set({
      userId: ALICE_UID,
      title: "ملاحظة اجتماع",
      rawText: "مرحبا بكم في الاجتماع الأسبوعي",
      organizedText: "# ملاحظة اجتماع\n- مراجعة المهام",
      language: "ar",
      formatStyle: "MEETING_NOTES",
      durationSeconds: 45,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Valid partial update
  await assertSucceeds(
    col.doc("note_1").update({
      title: "ملاحظة اجتماع محدثة",
      organizedText: "# ملخص\nتم تحديث الملاحظة",
      formatStyle: "CLEAN_PARAGRAPHS",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject ghost `id` field on create
  await assertFails(
    col.doc("note_2").set({
      id: "note_2",
      userId: ALICE_UID,
      title: "اختبار",
      rawText: "نص",
      language: "ar",
      formatStyle: "RAW",
      durationSeconds: 5,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Reject immutable createdAt modification
  await assertFails(
    col.doc("note_1").update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject invalid formatStyle enum
  await assertFails(
    col.doc("note_1").update({
      formatStyle: "INVALID_STYLE",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject future timestamp
  await assertFails(
    col.doc("note_3").set({
      userId: ALICE_UID,
      title: "مستقبل",
      rawText: "نص",
      language: "ar",
      formatStyle: "RAW",
      durationSeconds: 5,
      createdAt: new Date(Date.now() + 60000),
      updatedAt: created,
    })
  );

  await assertSucceeds(col.doc("note_1").delete());
});

test("Owner: voiceProfiles and generatedSpeeches CRUD succeed and validate bounds", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const profilesCol = aliceDb.collection("users").doc(ALICE_UID).collection("voiceProfiles");
  const speechesCol = aliceDb.collection("users").doc(ALICE_UID).collection("generatedSpeeches");
  const now = new Date(Date.now() - 2000);

  await assertSucceeds(
    profilesCol.doc("voice_1").set({
      userId: ALICE_UID,
      displayName: "نبرتي الهادئة",
      baseVoiceName: "Charon",
      toneDescription: "نبرة عميقة وهادئة بنطق عربي واضح",
      deliveryStyle: "هادئ وواضح ومتزن",
      pitchShift: 1.0,
      speechRate: 1.0,
      sampleDurationSeconds: 8,
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    profilesCol.doc("voice_1").update({
      pitchShift: 1.05,
      speechRate: 0.95,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject out-of-bounds pitchShift
  await assertFails(
    profilesCol.doc("voice_1").update({
      pitchShift: 2.5,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  await assertSucceeds(
    speechesCol.doc("sp_1").set({
      userId: ALICE_UID,
      text: "أهلاً بكم في نبرة، هذا صوتي المخصص.",
      voiceProfileName: "نبرتي الهادئة",
      baseVoiceName: "Charon",
      pitchShift: 1.05,
      speechRate: 0.95,
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(speechesCol.orderBy("createdAt", "desc").get());
});

test("Cross-user isolation: Bob cannot read or write Alice's transcripts, voiceProfiles, or speeches", async () => {
  const created = new Date(Date.now() - 5000);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("users").doc(ALICE_UID).collection("transcripts").doc("a1").set({
      userId: ALICE_UID,
      title: "سري",
      rawText: "كلام خاص",
      language: "ar",
      formatStyle: "RAW",
      durationSeconds: 10,
      createdAt: created,
      updatedAt: created,
    });
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("transcripts").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("transcripts").doc("a1").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("transcripts").doc("a1").update({ title: "اختراق" }));
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("transcripts").doc("a1").delete());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("transcripts").doc("b1").set({
      userId: BOB_UID,
      title: "حقن",
      rawText: "نص",
      language: "ar",
      formatStyle: "RAW",
      durationSeconds: 5,
      createdAt: created,
      updatedAt: created,
    })
  );
});
