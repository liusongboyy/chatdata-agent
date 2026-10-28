package com.liusongboyy.chatdata.iam.admin;

/**
 * 管理面的可解释失败（输入不合法、对象不存在、重名……）。
 *
 * <p>控制器把它映射成 400 并把消息透给调用方——这些话是写给管理员看的。
 * 其它异常一律 500 + 通用提示，不把内部细节漏出去。
 */
public class IamAdminException extends RuntimeException {

    public IamAdminException(String message) {
        super(message);
    }
}
