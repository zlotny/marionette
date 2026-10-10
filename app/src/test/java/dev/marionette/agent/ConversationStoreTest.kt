package dev.marionette.agent

import dev.marionette.core.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ConversationStoreTest {
    private val dir: File = Files.createTempDirectory("chats").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun settle(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        Thread.sleep(50)
    }

    @Test
    fun savesTheLatestReply() {
        val store = Conversation(dir, scope)
        store.start(Turn(1, "hello", TurnSource.TEXT))
        repeat(20) { n -> store.update(1) { it.copy(reply = "reply $n", status = TurnStatus.ANSWERED) } }
        val file = File(dir, "${store.current.value.id}.json")
        settle { file.exists() && file.readText().contains("reply 19") }
        val saved = AppJson.decodeFromString(Chat.serializer(), file.readText())
        assertEquals("reply 19", saved.turns.single().reply)
        assertFalse(File(dir, "${store.current.value.id}.json.tmp").exists())
    }

    @Test
    fun deletedChatStaysDeleted() {
        val store = Conversation(dir, scope)
        store.start(Turn(1, "hello", TurnSource.TEXT))
        val id = store.current.value.id
        store.update(1) { it.copy(reply = "done", status = TurnStatus.ANSWERED) }
        store.delete(id)
        settle { false }
        assertFalse(File(dir, "$id.json").exists())
    }
}
