package com.gowtham.letschat.services

import android.content.Context
import android.net.Uri
import androidx.hilt.Assisted
import androidx.hilt.work.WorkerInject
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.storage.StorageReference
import com.gowtham.letschat.TYPE_NEW_GROUP_MESSAGE
import com.gowtham.letschat.TYPE_NEW_MESSAGE
import com.gowtham.letschat.core.GroupMsgSender
import com.gowtham.letschat.core.MessageSender
import com.gowtham.letschat.core.OnGrpMessageResponse
import com.gowtham.letschat.core.OnMessageResponse
import com.gowtham.letschat.db.daos.GroupDao
import com.gowtham.letschat.db.daos.GroupMessageDao
import com.gowtham.letschat.db.data.ChatUser
import com.gowtham.letschat.db.data.Group
import com.gowtham.letschat.db.data.GroupMessage
import com.gowtham.letschat.db.data.Message
import com.gowtham.letschat.di.GroupCollection
import com.gowtham.letschat.utils.Constants
import com.gowtham.letschat.utils.UserUtils
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.concurrent.CountDownLatch

class GroupUploadWorker @WorkerInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val storageRef: StorageReference,
    @GroupCollection
    val groupCollection: CollectionReference,
    val groupDao: GroupDao,
    val messageDao: GroupMessageDao):
    Worker(appContext, workerParams) {

    private val params=workerParams

    override fun doWork(): Result {
        val stringData=params.inputData.getString(Constants.MESSAGE_DATA) ?: ""
        val message= Json.decodeFromString<GroupMessage>(stringData)

        val createdAt=message.createdAt.toString()
        val num=createdAt.substring(createdAt.length - 5)
        val url=message.imageMessage?.uri.toString()
        val format=url.substring(url.lastIndexOf('.'))
        val sourceName="${message.imageMessage?.imageType}$num$format"

        val child = storageRef.child(
            "group/${message.to}/$sourceName")
        val task = child.putFile(Uri.parse(url))

        val countDownLatch = CountDownLatch(1)
        val result= arrayOf(Result.failure())
        task.addOnSuccessListener {
            child.downloadUrl.addOnCompleteListener { taskResult ->
                Timber.v("TaskResult ${taskResult.result.toString()}")
                val imgUrl=taskResult.result.toString()
                sendMessage(message,imgUrl,result,countDownLatch)
            }.addOnFailureListener { e ->
                Timber.v("TaskResult Failed ${e.message}")
                result[0]= Result.failure()
                message.status[0]=4
                UserUtils.insertGroupMsg(messageDao, message)
                countDownLatch.countDown()
            }
        }.addOnProgressListener { taskSnapshot ->
            val progress: Double =
                100.0 * taskSnapshot.bytesTransferred / taskSnapshot.totalByteCount
        }
        countDownLatch.await()
        return result[0]
    }

    private fun sendMessage(
        message: GroupMessage,imgUrl: String,
        result: Array<Result>,
        countDownLatch: CountDownLatch) {
        val group=Json.decodeFromString<Group>(params.inputData.getString(Constants.GROUP_DATA)!!)
        message.imageMessage?.uri=imgUrl
        val messageSender = GroupMsgSender(groupCollection, groupDao)
        messageSender.sendMessage(message, group, object : OnGrpMessageResponse{
            override fun onSuccess(message: GroupMessage) {
                sendPushToMembers(group,message)
                result[0]= Result.success()
                countDownLatch.countDown()
            }

            override fun onFailed(message: GroupMessage) {
                result[0]= Result.failure()
                UserUtils.insertGroupMsg(messageDao, message)
                countDownLatch.countDown()
            }
        })
    }

    private fun sendPushToMembers(group: Group, message: GroupMessage) {
        val users = group.members?.filter { it.user.token.isNotEmpty() }?.map {
            it.user.token
            it
        }
        users?.forEach {
            UserUtils.sendPush(
                applicationContext, TYPE_NEW_GROUP_MESSAGE,
                Json.encodeToString(message), it.user.token, it.id
            )
        }
    }


}
