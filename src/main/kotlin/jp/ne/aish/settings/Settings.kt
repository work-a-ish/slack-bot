package jp.ne.aish.settings

import org.yaml.snakeyaml.Yaml
import java.io.FileInputStream

/**
 * URL、DB、チャンネル、検索タグの情報をまとめたクラス.
 */
@Suppress("UNCHECKED_CAST")
object Settings {
    /**
     * settings.ymlをMap化したもの
     */
    private val settings = Yaml().load(FileInputStream("settings.yml")) as Map<String, *>

    /**
     * slack-botの投稿先URL
     */
    val URL = settings["slack_url"] as String

    /**
     * slack-botの投稿先チャンネルのリスト
     */
    val channel = settings["channel"] as Map<String, String>

    /**
     * Qiitaの検索タグのリスト
     */
    val tags = settings["tag"] as List<String>

    /**
     * QiitaのRSSフィードURLリスト
     */
    val qiitaRss: List<String> by lazy {
        tags.map {
            "https://qiita.com/tags/$it/feed.atom"
        }
    }
}