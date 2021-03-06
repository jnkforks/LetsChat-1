package com.gowtham.letschat.core

import android.content.Context
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.gowtham.letschat.FirebasePush
import com.gowtham.letschat.db.daos.ChatUserDao
import com.gowtham.letschat.db.daos.GroupDao
import com.gowtham.letschat.db.daos.GroupMessageDao
import com.gowtham.letschat.db.data.ChatUser
import com.gowtham.letschat.db.data.Group
import com.gowtham.letschat.db.data.GroupMessage
import com.gowtham.letschat.di.GroupCollection
import com.gowtham.letschat.fragments.single_chat.toDataClass
import com.gowtham.letschat.utils.LogMessage
import com.gowtham.letschat.utils.MPreference
import com.gowtham.letschat.utils.UserUtils
import com.gowtham.letschat.utils.Utils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupChatHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preference: MPreference,
    private val userCollection: CollectionReference,
    @GroupCollection
    private val groupCollection: CollectionReference,
    private val chatUserDao: ChatUserDao,
    private val groupDao: GroupDao,
    private val messageDao: GroupMessageDao) {

    private val userId = preference.getUid()

    private var messageCollectionGroup: Query

    private val messagesList: MutableList<GroupMessage> by lazy { mutableListOf() }

    private val listOfGroup = ArrayList<String>()

    private lateinit var chatUsers: List<ChatUser>

    init {
        Timber.v("GroupChatHandler init")
        preference.clearCurrentGroup()
        messageCollectionGroup = UserUtils.getGroupMsgSubCollectionRef()
        addGroupsSnapShotListener()
        addGroupMsgListener()
    }

    companion object{

        private var groupListener: ListenerRegistration?=null

        private var myProfileListener: ListenerRegistration?=null

        fun removeListener(){
            groupListener?.remove()
            myProfileListener?.remove()
        }
    }

    private fun addGroupMsgListener() {
       groupListener= messageCollectionGroup.whereArrayContains("to", userId!!)
            .addSnapshotListener { snapshots, error ->
                Timber.v("GroupMessageCollection listener called")
                if (error == null) {
                    messagesList.clear()
                    listOfGroup.clear()
                    if(snapshots==null) {
                        Timber.v("Snapshot is null")
                        return@addSnapshotListener
                    }
                    for (msgDoc in snapshots) {
                        if (msgDoc.id.toLong() < preference.getLogInTime())
                            continue    //ignore old messages
                        val message = msgDoc.data.toDataClass<GroupMessage>()
                        if (message.groupId == preference.getOnlineGroup()) { //would be updated by snapshot listener
                            Timber.v("Online group is ${message.groupId}")
                            continue
                        }
                        if (!listOfGroup.contains(message.groupId))
                            listOfGroup.add(message.groupId)
                        messagesList.add(message)
                    }
                    updateGroupUnReadCount()
                }else
                    Timber.v(error)
            }
    }

    private fun updateGroupUnReadCount() {
        CoroutineScope(Dispatchers.IO).launch {
            val groups=groupDao.getGroupList()
            for (group in groups){
                group.unRead=messagesList.filter {
                    val myStatus= Utils.myMsgStatus(userId.toString(),it)
                    it.from!=userId &&
                    it.groupId==group.id && myStatus<3
                }.size
                Timber.v("Group name ${group.id} Unread count ${group.unRead}")
            }
            updateInLocal(groups)
        }

    }

    private fun updateInLocal(groups: List<Group>) {
        val updateToSeen = GroupMsgStatusUpdater(groupCollection)
        updateToSeen.updateToDelivery(userId!!, messagesList, *listOfGroup.toTypedArray())
        CoroutineScope(Dispatchers.IO).launch {
            messageDao.insertMultipleMessage(messagesList)
            groupDao.insertMultipleGroup(groups)
        }
        if (groups.isNotEmpty())
            FirebasePush.showGroupNotification(context, chatUserDao, groupDao)
    }

    private fun addGroupsSnapShotListener() {
        myProfileListener= userCollection.document(userId.toString()).addSnapshotListener { snapshot, error ->
            if (error == null) {
                Timber.v("UserSnapShot listener called")
                val groups = snapshot?.get("groups")
                val listOfGroup = if (groups == null) ArrayList() else groups as ArrayList<String>
                CoroutineScope(Dispatchers.IO).launch {
                    val alreadySavedGroup = groupDao.getGroupList().map { it.id }
                    val removedGroups = alreadySavedGroup.toSet().minus(listOfGroup.toSet())
                    val newGroups = listOfGroup.toSet().minus(alreadySavedGroup.toSet())
                    withContext(Dispatchers.Main) {
                        queryNewGroups(newGroups)
                    }
                }
            }
        }
    }

    private fun queryNewGroups(newGroups: Set<String>) {
        Timber.v("New groups ${newGroups.size}")
        for (groupId in newGroups) {
            val groupQuery = GroupQuery(groupId, chatUserDao, groupDao, preference)
            groupQuery.getGroupData(groupCollection)
        }
    }

}