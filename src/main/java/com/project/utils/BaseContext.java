package com.project.utils;

public class BaseContext {
    // 线程局部变量
    private static final ThreadLocal<Long> threadLocal = new ThreadLocal<>();

    /**
     * 设置当前线程的id
     * @param id
     */
    public static void setCurrentId(Long id) {
        threadLocal.set(id);
    }

    /**
     * 获取当前线程的id
     * @return
     */
    public static Long getCurrentId() {
        return threadLocal.get();
    }

    /**
     * 删除当前线程的id
     */
    public static void removeCurrentId() {
        threadLocal.remove();
    }
}
