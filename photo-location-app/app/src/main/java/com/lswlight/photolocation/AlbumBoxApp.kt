package com.lswlight.photolocation

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder

/** 동영상도 썸네일이 나오도록 Coil 에 동영상 프레임 디코더를 등록한다. */
class AlbumBoxApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
}
