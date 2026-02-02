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
package dev.octoshrimpy.quik.interactor

import dev.octoshrimpy.quik.extensions.mapNotNull
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.repository.EmojiReactionRepository
import dev.octoshrimpy.quik.repository.MessageRepository
import io.reactivex.Flowable
import io.reactivex.android.schedulers.AndroidSchedulers
import timber.log.Timber
import javax.inject.Inject

class SendEmojiReaction @Inject constructor(
    private val conversationRepo: ConversationRepository,
    private val messageRepo: MessageRepository,
    private val emojiReactionRepo: EmojiReactionRepository,
    private val updateBadge: UpdateBadge
) : Interactor<SendEmojiReaction.Params>() {

    data class Params(
        val subId: Int,
        val threadId: Long,
        val targetMessageId: Long,
        val emoji: String,
        val isRemoval: Boolean = false
    )

    override fun buildObservable(params: Params): Flowable<*> = Flowable.just(Unit)
        .mapNotNull {
            conversationRepo.getOrCreateConversation(params.threadId)
                ?: let { Timber.e("unable to get conversation for threadId: ${params.threadId}"); null }
        }
        .mapNotNull { conversation ->
            val targetMessage = messageRepo.getMessage(params.targetMessageId)
            if (targetMessage == null) {
                Timber.e("unable to find target message: ${params.targetMessageId}")
                return@mapNotNull null
            }

            val targetMessageText = targetMessage.getText(false)
            val reactionBody = emojiReactionRepo.formatReactionSms(
                params.emoji,
                targetMessageText,
                params.isRemoval
            )

            val sentMessages = messageRepo.sendNewMessages(
                params.subId,
                conversation.recipients.map { it.address },
                reactionBody,
                emptyList(),
                false,
                0
            )

            if (params.isRemoval) {
                emojiReactionRepo.removeOutgoingReaction(params.subId, targetMessage, params.emoji)
            } else {
                val reactionMessageId = sentMessages.firstOrNull()?.id ?: 0L
                emojiReactionRepo.saveOutgoingReaction(
                    params.subId,
                    reactionMessageId,
                    targetMessage,
                    params.emoji,
                    params.threadId
                )
            }

            conversation.id
        }
        .doOnNext { threadId ->
            conversationRepo.updateConversations(listOf(threadId))
        }
        .observeOn(AndroidSchedulers.mainThread())
        .flatMap { updateBadge.buildObservable(Unit) }

}
