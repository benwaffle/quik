/*
 * Copyright (C) 2025
 *
 * This file is part of QUIK.
 *
 * QUIK is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QUIK is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QUIK.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.repository

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import dev.octoshrimpy.quik.data.R
import dev.octoshrimpy.quik.manager.KeyManager
import dev.octoshrimpy.quik.model.EmojiReaction
import dev.octoshrimpy.quik.model.Message
import io.realm.Realm
import io.realm.Sort
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject

class EmojiReactionRepositoryImpl @Inject constructor(
    private val context: Context,
    private val keyManager: KeyManager
) : EmojiReactionRepository {

    private val supportedLocales: Set<Locale> by lazy {
        val availableLocales = mutableSetOf<Locale>(Locale.ENGLISH)

        try {
            val systemLocales = Locale.getAvailableLocales()

            for (locale in systemLocales) {
                val localizedContext = getLocalizedContext(locale)
                try {
                    val testString = localizedContext.getString(R.string.emoji_reaction_google_messages_added)
                    val englishString = getLocalizedContext(Locale.ENGLISH).getString(R.string.emoji_reaction_google_messages_added)

                    if (testString != englishString) {
                        availableLocales.add(locale)
                        Timber.d("Found emoji pattern translations for locale: ${locale.toLanguageTag()}")
                    }
                } catch (e: Resources.NotFoundException) {
                    // This locale doesn't have our strings, skip it
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Error discovering available locales, using defaults")
        }

        Timber.i("Using emoji pattern locales: ${availableLocales.map { it.toLanguageTag() }}")
        availableLocales
    }

    private fun getLocalizedContext(locale: Locale): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    // We use an ordered map to make sure we test tapback regexes before generic ones
    private val reactionPatterns: LinkedHashMap<Regex, (MatchResult) -> ParsedEmojiReaction?> = linkedMapOf()
    private val removalPatterns: LinkedHashMap<Regex, (MatchResult) -> ParsedEmojiReaction?> = linkedMapOf()

    init {
        supportedLocales.forEach { locale ->
            val localizedContext = getLocalizedContext(locale)
            try {
                addPatternsForLocale(localizedContext, reactionPatterns, removalPatterns)
            } catch (e: Exception) {
                Timber.w(e, "Failed to load patterns for locale: ${locale.language}")
            }
        }
    }

    private fun addPatternsForLocale(
        context: Context,
        reactionPatterns: LinkedHashMap<Regex, (MatchResult) -> ParsedEmojiReaction?>,
        removalPatterns: LinkedHashMap<Regex, (MatchResult) -> ParsedEmojiReaction?>
    ) {
        // Google Messages
        val addedGoogleEmoji = Regex(context.getString(R.string.emoji_reaction_google_messages_added))
        reactionPatterns[addedGoogleEmoji] = { match ->
            ParsedEmojiReaction(match.groupValues[1], match.groupValues[2])
        }
        val removedGoogleEmoji = Regex(context.getString(R.string.emoji_reaction_google_messages_removed))
        removalPatterns[removedGoogleEmoji] = { match ->
            ParsedEmojiReaction(match.groupValues[1], match.groupValues[2], isRemoval = true)
        }

        // iOS tapbacks
        // these patterns must come before generic iOS patterns as the regexes can overlap
        mapOf(
            "ios_loved" to Triple("❤️", R.string.emoji_reaction_ios_loved_added, R.string.emoji_reaction_ios_loved_removed),
            "ios_liked" to Triple("👍", R.string.emoji_reaction_ios_liked_added, R.string.emoji_reaction_ios_liked_removed),
            "ios_disliked" to Triple("👎", R.string.emoji_reaction_ios_disliked_added, R.string.emoji_reaction_ios_disliked_removed),
            "ios_laughed" to Triple("😂", R.string.emoji_reaction_ios_laughed_added, R.string.emoji_reaction_ios_laughed_removed),
            "ios_emphasized" to Triple("‼️", R.string.emoji_reaction_ios_emphasized_added, R.string.emoji_reaction_ios_emphasized_removed),
            "ios_questioned" to Triple("❓", R.string.emoji_reaction_ios_questioned_added, R.string.emoji_reaction_ios_questioned_removed)
        ).forEach { (_, pattern) ->
            val (emoji, addedStringRes, removedStringRes) = pattern

            val addedRegex = Regex(context.getString(addedStringRes))
            reactionPatterns[addedRegex] = { match: MatchResult ->
                ParsedEmojiReaction(emoji, match.groupValues[1])
            }

            val removedRegex = Regex(context.getString(removedStringRes))
            removalPatterns[removedRegex] = { match: MatchResult ->
                ParsedEmojiReaction(emoji, match.groupValues[1], isRemoval = true)
            }
        }

        // Generic iOS emoji patterns
        val addediOSEmoji = Regex(context.getString(R.string.emoji_reaction_ios_generic_added))
        reactionPatterns[addediOSEmoji] = { match ->
            if (match.groupValues[1] == "with a sticker") null
            else ParsedEmojiReaction(match.groupValues[1], match.groupValues[2])
        }
        val removediOSEmoji = Regex(context.getString(R.string.emoji_reaction_ios_generic_removed))
        removalPatterns[removediOSEmoji] = { match ->
            ParsedEmojiReaction(match.groupValues[1], match.groupValues[2], isRemoval = true)
        }
    }

    override fun parseEmojiReaction(body: String): ParsedEmojiReaction? {
        val removal = parseRemoval(body)
        if (removal != null) return removal

        for ((pattern, parser) in reactionPatterns) {
            val match = pattern.find(body)
            if (match == null) continue;

            val result = parser(match)
            if (result == null) continue

            Timber.d("Reaction found with ${result.emoji}")
            return result
        }

        return null
    }

    private fun parseRemoval(body: String): ParsedEmojiReaction? {
        for ((pattern, parser) in removalPatterns) {
            val match = pattern.find(body)
            if (match == null) continue;

            val result = parser(match)
            if (result == null) continue

            Timber.d("Removal found with ${result.emoji}")
            return result
        }

        return null
    }

    /**
     * Search for messages in the same thread with matching text content
     * We'll search recent messages first
     */
    override fun findTargetMessage(
        threadId: Long,
        originalMessageText: String,
        realm: Realm
    ): Message? {
        val startTime = System.currentTimeMillis()
        val messages = realm.where(Message::class.java)
            .equalTo("threadId", threadId)
            .sort("date", Sort.DESCENDING)
            .findAll()
        val endTime = System.currentTimeMillis()
        Timber.d("Found ${messages.size} messages as emoji targets in ${endTime - startTime}ms")

        val match = messages.find { message ->
            message.getText(false).trim() == originalMessageText.trim()
        }
        if (match != null) {
            Timber.d("Found match for reaction target: message ID ${match.id}")
            return match
        }

        Timber.w("No target message found for reaction text: '$originalMessageText'")
        return null
    }

    private fun removeEmojiReaction(
        reactionMessage: Message,
        reaction: ParsedEmojiReaction,
        targetMessage: Message?,
    ) {
        if (targetMessage == null) {
            Timber.w("Cannot remove emoji reaction '${reaction.emoji}': no target message found")
            reactionMessage.isEmojiReaction = true
            return
        }

        val existingReaction = targetMessage.emojiReactions.find { candidate ->
            candidate.senderAddress == reactionMessage.address && candidate.emoji == reaction.emoji
        }

        if (existingReaction != null) {
            existingReaction.deleteFromRealm()
            Timber.d("Removed emoji reaction: ${reaction.emoji} to message ${targetMessage.id}")
        } else {
            Timber.w("No existing emoji reaction found to remove: ${reaction.emoji} to message ${targetMessage.id}")
        }

        reactionMessage.isEmojiReaction = true
    }

    override fun saveEmojiReaction(
        reactionMessage: Message,
        parsedReaction: ParsedEmojiReaction,
        targetMessage: Message?,
        realm: Realm,
    ) {
        if (parsedReaction.isRemoval) {
            removeEmojiReaction(reactionMessage, parsedReaction, targetMessage)
            return
        }

        val reaction = EmojiReaction().apply {
            id = keyManager.newId()
            reactionMessageId = reactionMessage.id
            senderAddress = reactionMessage.address
            emoji = parsedReaction.emoji
            originalMessageText = parsedReaction.originalMessage
            threadId = reactionMessage.threadId
        }
        realm.insertOrUpdate(reaction)

        reactionMessage.isEmojiReaction = true

        if (targetMessage != null) {
            targetMessage.emojiReactions.add(reaction)

            Timber.i("Saved emoji reaction: ${reaction.emoji} to message ${targetMessage.id}")
        } else {
            Timber.w("Saved emoji reaction without target message: ${reaction.emoji}")
        }
    }

    override fun deleteAndReparseAllEmojiReactions(realm: Realm) {
        val startTime = System.currentTimeMillis()

        realm.delete(EmojiReaction::class.java)
        realm.where(Message::class.java).findAll().map {
            it.isEmojiReaction = false
        }

        val allMessages = realm.where(Message::class.java)
            .beginGroup()
                .beginGroup()
                    .equalTo("type", "sms")
                    .isNotEmpty("body")
                .endGroup()
                .or()
                .beginGroup()
                    .equalTo("type", "mms")
                    .isNotEmpty("parts.text")
                .endGroup()
            .endGroup()
            .sort("date", Sort.ASCENDING) // parse oldest to newest to handle reactions & removals properly
            .findAll()

        allMessages.forEach { message ->
            val text = message.getText(false)
            val parsedReaction = parseEmojiReaction(text)
            if (parsedReaction != null) {
                val targetMessage = findTargetMessage(
                    message.threadId,
                    parsedReaction.originalMessage,
                    realm
                )
                saveEmojiReaction(
                    message,
                    parsedReaction,
                    targetMessage,
                    realm,
                )
            }
        }

        val endTime = System.currentTimeMillis()
        Timber.d("Deleted and reparsed all emoji reactions in ${endTime - startTime}ms")
    }

}
