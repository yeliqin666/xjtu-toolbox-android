package com.xjtu.toolbox.agent

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.account.restoreIfNeeded
import com.xjtu.toolbox.platform.keyValueStore
import java.io.File

internal fun androidAgentShareStore(): AgentShareStore {
    AccountContext.restoreIfNeeded()
    return AgentShareStore(keyValueStore("agent_shares" + AccountContext.safeSuffix()),
        deleteImages = { paths -> paths.forEach { File(it).delete() } })
}

internal fun receiveAgentShare(text: String, images: List<String> = emptyList()) {
    if (androidAgentShareStore().add(text, images) != null) AgentShareInbox.received()
}
