package jp.ne.aish

import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.result.Result
import com.google.gson.Gson
import jp.ne.aish.data.QiitaFeedData
import jp.ne.aish.settings.Settings
import org.slf4j.LoggerFactory
import org.w3c.dom.Element
import java.io.File
import java.sql.DriverManager
import java.sql.SQLException
import javax.xml.parsers.DocumentBuilderFactory

private typealias feedItems = Pair<String, List<QiitaFeedData>>

fun main(args: Array<String>) {
    SlackBot().checkRSSFeed()
}

/**
 * SlackBotのメインクラス
 */
class SlackBot {
    /**
     * ロガー
     */
    private val logger = LoggerFactory.getLogger(javaClass)
    /**
     * sqliteのパス
     */
    private val databasePath = File("qiitafeed.sql").path
    /**
     * DBのURL
     */
    private val databaseUrl = "jdbc:sqlite:$databasePath"


    /**
     * RSSフィードを取得して新着があればSlackに投稿する
     */
    fun checkRSSFeed() {
        logger.info("start checking...")

        // タグ検索で取得したRSSフィードのリスト
        val qiitaFeedList = fetchQiitaFeed(Settings.qiitaRss)
        // DBを取得して更新されたフィードだけのリストにする
        val latestFeed = qiitaFeedList.map { createLatestFeed(it) }

        if (latestFeed.all { it.second.isEmpty() }) { // 更新がなかったら終了
            logger.info("nothing updated")
            return
        }

        // 更新があったタグだけDB更新と投稿をする
        latestFeed.forEach {
            if (it.second.isNotEmpty()) {
                // DBを更新する
                updateDB(it)
                // slackに投稿する
                postToSlack(it)
            }
        }

        logger.info("checking finished")
    }

    /**
     * QiitaのRSSフィードを取得する.TagNameの関係でQiita専用
     * @param urls フィード取得元のURLのリスト
     * @return Pair<検索タグ,feedItemのリスト> のリスト
     */
    private fun fetchQiitaFeed(urls: List<String>): List<feedItems> {
        val root = urls.map {
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(it)
                .documentElement
        }
        // RSSアイテムの取得
        val entries = root.map {
            it.getElementsByTagName("entry")
        }
        // feedDataに格納してtagごとのリストにする
        val feedDataList = entries.map { nodeList ->
            (0 until nodeList.length).map {
                val element = nodeList.item(it) as Element
                val id = element.getElementsByTagName("id").item(0).firstChild.nodeValue
                val updated = element.getElementsByTagName("updated").item(0).firstChild.nodeValue
                val url = element.getElementsByTagName("url").item(0).firstChild.nodeValue
                val title = element.getElementsByTagName("title").item(0).firstChild.nodeValue

                QiitaFeedData(id, updated, url, title)
            }
        }
        return Settings.tags.zip(feedDataList)
    }

    /**
     * DBを確認して最新の履歴だけのリストを作る
     * @param data フィードから取得した全てのデータリスト
     * @return DBに入力されていないデータだけのリスト
     */
    private fun createLatestFeed(data: feedItems): feedItems {
        // テーブルのidとupdatedが両方とも一致するかどうか
        val query = "select * from ${data.first} where exists(select * from ${data.first} where id=? and updated=?)"
        try {
            DriverManager.getConnection(databaseUrl).use { connection ->
                return Pair(data.first, data.second.filterNot { feedItemsData ->
                    connection.prepareStatement(query).use { preparedStatement ->
                        preparedStatement.setString(1, feedItemsData.id)
                        preparedStatement.setString(2, feedItemsData.updated)
                        preparedStatement.executeQuery().next()
                    }
                })
            }
        } catch (e: SQLException) {
            logger.warn("DBとの接続中にエラーが発生しました。", e)
            throw e
        }
    }

    /**
     * DBを更新する
     * @param data DBに投げる更新情報のリスト
     */
    private fun updateDB(data: feedItems) {
        val query = "insert into ${data.first} (id,updated,url,title) values(?,?,?,?)"

        try {
            DriverManager.getConnection(databaseUrl).use { connection ->
                try {
                    // トランザクション開始
                    connection.autoCommit = false
                    // 全行のSQLを作ってからまとめて実行
                    connection.prepareStatement(query).use { preparedStatement ->
                        data.second.forEach {
                            preparedStatement.setString(1, it.id)
                            preparedStatement.setString(2, it.updated)
                            preparedStatement.setString(3, it.url)
                            preparedStatement.setString(4, it.title)

                            preparedStatement.addBatch()
                        }
                        preparedStatement.executeBatch()
                    }
                    connection.commit()
                    logger.info("DB更新が正常に終了しました。")
                } catch (e: SQLException) { // 更新中に失敗したらログを出力してロールバック
                    logger.warn("DBの更新でエラーが発生しました。", e)
                    connection.rollback()
                    throw e
                }
            }
        } catch (e: SQLException) {
            logger.warn("DBとの接続でエラーが発生しました。", e)
            throw e
        }
    }

    /**
     * Slackに投稿する
     * @param feedList チャンネル名と取得したフィードのリスト
     */
    private fun postToSlack(feedList: feedItems) {
        // api接続先のURL
        val url = Settings.URL

        // 投稿するためのpayloadを作成
        val payload = generatePayload(feedList)

        // postする
        payload.map {
            Fuel.post(url)
                .body("payload=$it")
                .responseString()
        }.forEach { (_, response, fuelResult) ->
            when (fuelResult) {
                is Result.Success ->
                    logger.info("success")
                is Result.Failure ->
                    logger.warn(String(response.data, Charsets.UTF_8))
            }
        }
    }

    /**
     * payloadを生成する
     * @param feedList チャンネル名と取得したフィードのリスト
     * @return payloadのリスト
     */
    private fun generatePayload(feedList: feedItems): List<String> {

        // 更新通知の冒頭
        val firstBlockString =
            mapOf(
                "type" to "section",
                "text" to mapOf(
                    "type" to "mrkdwn",
                    "text" to "更新がありました。内容は以下の通りです。"
                )
            )

        // 区切り線
        val divider = mapOf("type" to "divider")

        // チャンネル名と投稿する項目のmap
        val description = feedList.second.map {
            mapOf(
                "type" to "section",
                "text" to mapOf(
                    "type" to "mrkdwn",
                    "text" to """
                        |
                        |*更新日時: ${it.updated}*
                        |記事タイトル: ${it.title}
                        |リンク: <${it.url}>
                        |
                        """.trimMargin()
                )
            )
        }

        // 冒頭文、通知リスト、dividerを成形したJson文字列
        val blocks = listOf(firstBlockString, divider) + description.flatMap { listOf(it, divider) }


        // API側の仕様でblocksのアイテム数は50が上限のため、超える場合には分割する
        val blocksList = if (blocks.size > 50) {
            blocks.chunked(50)
        } else {
            listOf(blocks)
        }

        // bodyに入力するためのpayloadのリスト
        return blocksList.map {
            Gson().toJson(
                mapOf(
                    "channel" to Settings.channel["qiita"] + feedList.first,
                    "username" to "更新通知",
                    "blocks" to it
                )
            )
        }
    }
}