package com.xjtu.toolbox.bus

import com.xjtu.toolbox.core.net.createToolboxClient

/** :app 侧的校车装配。进程级一份，别每次新建连接池；接口不用登录，也不分账号。 */
val appBusApi: BusApi by lazy { BusApi(createToolboxClient()) }
