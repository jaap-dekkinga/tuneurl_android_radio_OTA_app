package com.tuneurlradio.app.data.remote

import com.tuneurlradio.app.domain.model.NewsArticle
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.xml.parsers.SAXParserFactory

class RssFeedParser {

    fun parse(xmlData: String): List<NewsArticle> {
        val handler = RssHandler()
        val factory = SAXParserFactory.newInstance()
        val parser = factory.newSAXParser()
        parser.parse(xmlData.byteInputStream(), handler)
        return handler.articles
    }

    private class RssHandler : DefaultHandler() {
        val articles = mutableListOf<NewsArticle>()

        private var currentElement = ""
        private var currentTitle = StringBuilder()
        private var currentDescription = StringBuilder()
        private var currentLink = StringBuilder()
        private var currentPubDate = StringBuilder()
        private var currentCategories = mutableListOf<String>()
        private var currentCategoryText = StringBuilder()
        private var insideItem = false

        override fun startElement(
            uri: String?,
            localName: String?,
            qName: String?,
            attributes: Attributes?
        ) {
            currentElement = qName ?: localName ?: ""

            if (currentElement == "item") {
                insideItem = true
                currentTitle.clear()
                currentDescription.clear()
                currentLink.clear()
                currentPubDate.clear()
                currentCategories.clear()
            } else if (currentElement == "category") {
                currentCategoryText.clear()
            }
        }

        override fun characters(ch: CharArray?, start: Int, length: Int) {
            if (ch == null || !insideItem) return
            val text = String(ch, start, length)

            when (currentElement) {
                "title" -> currentTitle.append(text)
                "description" -> currentDescription.append(text)
                "link" -> currentLink.append(text)
                "pubDate" -> currentPubDate.append(text)
                "category" -> currentCategoryText.append(text)
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val element = qName ?: localName ?: ""

            if (element == "category" && insideItem) {
                val cat = currentCategoryText.toString().trim()
                if (cat.isNotEmpty()) {
                    currentCategories.add(cat)
                }
                currentCategoryText.clear()
            }

            if (element == "item") {
                val categories = if (currentCategories.isEmpty()) {
                    listOf("Uncategorized")
                } else {
                    currentCategories.toList()
                }

                val article = NewsArticle(
                    title = currentTitle.toString().trim(),
                    summary = currentDescription.toString().trim(),
                    link = currentLink.toString().trim().takeIf { it.isNotEmpty() },
                    pubDate = parsePubDate(currentPubDate.toString().trim()),
                    categories = categories
                )
                articles.add(article)
                insideItem = false
            }

            currentElement = ""
        }

        private fun parsePubDate(dateString: String): Date? {
            if (dateString.isEmpty()) return null
            return try {
                val formatter = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
                formatter.parse(dateString)
            } catch (e: Exception) {
                null
            }
        }
    }
}
