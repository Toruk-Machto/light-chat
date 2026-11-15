package sg.lightchat

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.toolmanager.ClientLeafNode
import com.thelightphone.toolmanager.ClientToolManifest
import com.thelightphone.toolmanager.FileBrowserSpec

internal const val IMPORTS = "imports"

@EntryPoint
object ToolEntryPoint : LightEntryPoint {
    override fun getToolManagerManifest() = ClientToolManifest(
        title = "Light Chat",
        roots = listOf(ClientLeafNode(FileBrowserSpec(
            label = "Imports",
            path = IMPORTS,
            headerText = "Upload photos, PDFs, and text files to attach in Light Chat.",
        ))),
    )
}
