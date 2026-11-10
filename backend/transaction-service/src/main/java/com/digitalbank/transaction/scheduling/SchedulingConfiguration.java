package com.digitalbank.transaction.scheduling;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 启用非终态原单的定时恢复扫描（交接文档 7.1：进程启动后以及每 10 秒扫描一次）。 */
@Configuration
@EnableScheduling
public class SchedulingConfiguration {
}
