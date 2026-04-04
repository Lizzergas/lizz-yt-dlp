#import <Foundation/Foundation.h>
#import <AVFoundation/AVFoundation.h>
#import <CoreMedia/CoreMedia.h>
#import <AudioToolbox/AudioToolbox.h>

#include "config.h"
#include "ytdl_ios_media.h"
#include "lame.h"

static int ytdl_transcode_asset_to_mp3(AVURLAsset *asset, NSString *output, int bitrate_kbps) {
    NSArray<AVAssetTrack *> *tracks = [asset tracksWithMediaType:AVMediaTypeAudio];
    if (tracks.count == 0) {
        return -2;
    }

    AVAssetTrack *track = tracks.firstObject;
    NSDictionary *outputSettings = @{
        AVFormatIDKey: @(kAudioFormatLinearPCM),
        AVLinearPCMIsFloatKey: @NO,
        AVLinearPCMBitDepthKey: @16,
        AVLinearPCMIsBigEndianKey: @NO,
        AVLinearPCMIsNonInterleaved: @NO,
    };

    NSError *error = nil;
    AVAssetReader *reader = [[AVAssetReader alloc] initWithAsset:asset error:&error];
    if (reader == nil) {
        return -3;
    }

    AVAssetReaderTrackOutput *trackOutput = [[AVAssetReaderTrackOutput alloc] initWithTrack:track outputSettings:outputSettings];
    if (![reader canAddOutput:trackOutput]) {
        return -4;
    }
    [reader addOutput:trackOutput];

    if (![reader startReading]) {
        return -5;
    }

    int sampleRate = 44100;
    int channels = 2;
    if (track.formatDescriptions.count > 0) {
        CMAudioFormatDescriptionRef desc = (__bridge CMAudioFormatDescriptionRef)track.formatDescriptions.firstObject;
        const AudioStreamBasicDescription *streamDesc = CMAudioFormatDescriptionGetStreamBasicDescription(desc);
        if (streamDesc != NULL) {
            sampleRate = (int)streamDesc->mSampleRate;
            channels = (int)streamDesc->mChannelsPerFrame;
            if (channels <= 0) channels = 2;
        }
    }

    lame_t lame = lame_init();
    if (lame == NULL) {
        return -6;
    }
    lame_set_in_samplerate(lame, sampleRate);
    lame_set_num_channels(lame, channels);
    lame_set_VBR(lame, vbr_default);
    lame_set_brate(lame, bitrate_kbps);
    lame_set_quality(lame, 2);
    if (lame_init_params(lame) < 0) {
        lame_close(lame);
        return -7;
    }

    FILE *out = fopen(output.UTF8String, "wb");
    if (out == NULL) {
        lame_close(lame);
        return -8;
    }

    unsigned char mp3Buffer[65536];
    while (reader.status == AVAssetReaderStatusReading) {
        CMSampleBufferRef sampleBuffer = [trackOutput copyNextSampleBuffer];
        if (sampleBuffer == NULL) {
            break;
        }

        CMBlockBufferRef blockBuffer = CMSampleBufferGetDataBuffer(sampleBuffer);
        if (blockBuffer != NULL) {
            size_t lengthAtOffset = 0;
            size_t totalLength = 0;
            char *dataPointer = NULL;
            OSStatus status = CMBlockBufferGetDataPointer(blockBuffer, 0, &lengthAtOffset, &totalLength, &dataPointer);
            if (status == kCMBlockBufferNoErr && dataPointer != NULL && totalLength > 0) {
                int samplesPerChannel = (int)(totalLength / sizeof(short) / channels);
                int encoded = 0;
                if (channels == 1) {
                    encoded = lame_encode_buffer(lame, (short int *)dataPointer, NULL, samplesPerChannel, mp3Buffer, sizeof(mp3Buffer));
                } else {
                    encoded = lame_encode_buffer_interleaved(lame, (short int *)dataPointer, samplesPerChannel, mp3Buffer, sizeof(mp3Buffer));
                }
                if (encoded > 0) {
                    fwrite(mp3Buffer, 1, encoded, out);
                }
            }
        }

        CFRelease(sampleBuffer);
    }

    int flushed = lame_encode_flush(lame, mp3Buffer, sizeof(mp3Buffer));
    if (flushed > 0) {
        fwrite(mp3Buffer, 1, flushed, out);
    }

    fclose(out);
    lame_close(lame);

    if (reader.status == AVAssetReaderStatusFailed) {
        return -9;
    }

    return 0;
}

ytdl_lame_handle ytdl_lame_init(int sample_rate, int channel_count, int bitrate_kbps) {
    lame_t lame = lame_init();
    if (lame == NULL) {
        return NULL;
    }
    lame_set_in_samplerate(lame, sample_rate);
    lame_set_num_channels(lame, channel_count);
    lame_set_VBR(lame, vbr_default);
    lame_set_brate(lame, bitrate_kbps);
    lame_set_quality(lame, 2);
    if (lame_init_params(lame) < 0) {
        lame_close(lame);
        return NULL;
    }
    return lame;
}

int ytdl_lame_encode_interleaved(ytdl_lame_handle handle, const short* pcm, int samples_per_channel, unsigned char* output, int output_size) {
    if (handle == NULL) return -1;
    return lame_encode_buffer_interleaved((lame_t)handle, (short int*)pcm, samples_per_channel, output, output_size);
}

int ytdl_lame_flush(ytdl_lame_handle handle, unsigned char* output, int output_size) {
    if (handle == NULL) return -1;
    return lame_encode_flush((lame_t)handle, output, output_size);
}

void ytdl_lame_close(ytdl_lame_handle handle) {
    if (handle != NULL) {
        lame_close((lame_t)handle);
    }
}

int ytdl_transcode_file_to_mp3(const char* input_path, const char* output_path, int bitrate_kbps) {
    @autoreleasepool {
        if (input_path == NULL || output_path == NULL) {
            return -1;
        }

        NSString *input = [NSString stringWithUTF8String:input_path];
        NSString *output = [NSString stringWithUTF8String:output_path];
        NSURL *inputUrl = [NSURL fileURLWithPath:input];
        AVURLAsset *asset = [AVURLAsset URLAssetWithURL:inputUrl options:nil];
        return ytdl_transcode_asset_to_mp3(asset, output, bitrate_kbps);
    }
}

int ytdl_transcode_url_to_mp3(const char* input_url, const char* output_path, const char* user_agent, const char* referer, const char* origin, int bitrate_kbps) {
    @autoreleasepool {
        if (input_url == NULL || output_path == NULL) {
            return -1;
        }

        NSString *input = [NSString stringWithUTF8String:input_url];
        NSString *output = [NSString stringWithUTF8String:output_path];
        NSURL *assetUrl = [NSURL URLWithString:input];
        if (assetUrl == nil) {
            return -10;
        }

        NSMutableDictionary *headerFields = [NSMutableDictionary dictionary];
        if (user_agent != NULL) {
            headerFields[@"User-Agent"] = [NSString stringWithUTF8String:user_agent];
        }
        if (referer != NULL) {
            headerFields[@"Referer"] = [NSString stringWithUTF8String:referer];
        }
        if (origin != NULL) {
            headerFields[@"Origin"] = [NSString stringWithUTF8String:origin];
        }

        NSDictionary *options = headerFields.count > 0 ? @{@"AVURLAssetHTTPHeaderFieldsKey": headerFields} : nil;
        AVURLAsset *asset = [AVURLAsset URLAssetWithURL:assetUrl options:options];
        return ytdl_transcode_asset_to_mp3(asset, output, bitrate_kbps);
    }
}
