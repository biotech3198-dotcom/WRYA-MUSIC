import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands

fun main() {
    val a = MediaLibrarySession.Callback::class.java
    val builder = SessionCommands.Builder()
    builder.addAllLibraryCommands()
    val commands = builder.build()
    println("Test passed")
}
