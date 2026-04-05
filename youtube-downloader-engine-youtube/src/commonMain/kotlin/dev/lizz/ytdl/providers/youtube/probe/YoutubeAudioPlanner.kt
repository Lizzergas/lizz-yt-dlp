package dev.lizz.ytdl.providers.youtube.probe

import dev.lizz.ytdl.providers.youtube.NativeAudioFormat
import dev.lizz.ytdl.providers.youtube.NativeManifest
import dev.lizz.ytdl.providers.youtube.ResolvedYoutubeMedia

internal object YoutubeAudioPlanner {
    fun plan(
        media: ResolvedYoutubeMedia,
        manifestPriority: List<String>,
        preferredFormatPicker: (List<NativeAudioFormat>) -> NativeAudioFormat,
    ): YoutubeAudioPlan {
        val directFormats = media.audioFormats.filter { it.url != null }
        val preferredDirectFormat = directFormats.takeIf { it.isNotEmpty() }?.let { preferredFormatPicker(it) }
        val orderedDirectFormats = if (preferredDirectFormat == null) {
            emptyList()
        } else {
            listOf(preferredDirectFormat) + directFormats.filterNot { it.url == preferredDirectFormat.url }
        }

        val manifestsByKind = (media.dashManifestUrls + media.hlsManifestUrls).groupBy { it.kind }
        val orderedManifests = buildList {
            manifestPriority.forEach { kind ->
                addAll(manifestsByKind[kind].orEmpty())
            }
        }

        return YoutubeAudioPlan(
            preferredDirectFormat = preferredDirectFormat,
            directFormats = orderedDirectFormats,
            manifestFallbacks = orderedManifests,
        )
    }
}
