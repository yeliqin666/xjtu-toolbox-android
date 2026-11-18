# 动态课程与日程通知

设置 → 通知与提醒 → 动态通知，默认关闭。开启后静默展示今天正在进行的一项安排，没有进行项时展示下一项；展开可看今日尚未结束的课程、日程数量（含当前项），点按进入日程页。今天没有剩余安排时收起，次日重新计算。

- 数据来自当前账号本学期的本地课表与自建日程，与日程页共用周次和冬夏作息。节假日不展示教务课程，自建课程与日程保留。缺少学期开始日期时不猜周次，提示先同步日程。
- 不为通知额外联网、查考勤，不展示未核实的打卡状态。
- 倒计时用系统通知的 chronometer：课前倒数到开始，进行中倒数到结束。使用系统通知模板，随系统深浅色、字号与厂商样式适配，不是厂商专有的超级岛 / 流体云。
- 独立 LOW 渠道：不响铃、不振动、不计角标，锁屏内容默认私密。

## 排程与权限

- 开启时按需申请通知权限，拒绝则保持关闭；系统设置里可单独关闭「动态课程与日程」渠道。
- 「准时切换通知」引导到系统「闹钟和提醒」授权。允许后用 `setExactAndAllowWhileIdle`，未允许回退 `setAndAllowWhileIdle`，后台切换可能被推迟；应用回到前台立即重算。
- 每次只排一个下一边界闹钟（任一安排的开始、结束或校园时区午夜），没有常驻前台服务，也不按分钟起 Worker。
- 通知设了超时，到下一边界先撤旧内容，宁可短暂没有卡片也不显示过时的倒计时。强行停止应用、撤回精确闹钟权限都会清掉排程，需重新打开应用恢复。
- 课表同步、日程增删改、助手写入日程、切换账号、退出、清缓存、回到前台、重启、换包、改系统时间后都会重算。只读持久化的激活账号，读取期间切号则丢弃旧轮次。关闭功能会撤下通知并取消闹钟。

## 测试

`DynamicSchedulePlanTest` 覆盖时间边界、重叠、节假日、分钟级日程、冬夏作息、空课表、周次与校园时区；`DynamicScheduleNotificationTest` 用 Robolectric 验证倒计时、点击路由、渠道属性、超时、单通知覆盖、取消与精确闹钟降级。

```powershell
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug -PdebugSuffix --no-configuration-cache
```

真机验收：今天加一个几分钟后开始的自建日程再开启动态通知，检查倒计时、开始 / 结束切换、点按跳转、编辑 / 删除后刷新、关闭后撤销与切号隔离；精确授权与未授权两种模式各测一次。

参考：[闹钟调度与精确权限](https://developer.android.com/develop/background-work/services/alarms)、[通知计时器](https://developer.android.com/reference/androidx/core/app/NotificationCompat.Builder#setChronometerCountDown(boolean))。
