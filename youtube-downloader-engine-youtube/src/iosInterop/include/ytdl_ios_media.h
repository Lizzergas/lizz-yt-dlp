#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef void* ytdl_lame_handle;

ytdl_lame_handle ytdl_lame_init(int sample_rate, int channel_count, int bitrate_kbps);
int ytdl_lame_encode_interleaved(ytdl_lame_handle handle, const short* pcm, int samples_per_channel, unsigned char* output, int output_size);
int ytdl_lame_flush(ytdl_lame_handle handle, unsigned char* output, int output_size);
void ytdl_lame_close(ytdl_lame_handle handle);
int ytdl_transcode_file_to_mp3(const char* input_path, const char* output_path, int bitrate_kbps);
int ytdl_transcode_url_to_mp3(const char* input_url, const char* output_path, const char* user_agent, const char* referer, const char* origin, int bitrate_kbps);

#ifdef __cplusplus
}
#endif
