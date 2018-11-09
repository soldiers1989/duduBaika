package com.dudubaika.base

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.support.v4.content.FileProvider
import android.view.LayoutInflater
import com.liulishuo.filedownloader.BaseDownloadTask
import com.liulishuo.filedownloader.FileDownloadListener
import com.liulishuo.filedownloader.FileDownloader
import com.tbruyelle.rxpermissions2.RxPermissions
import com.dudubaika.R
import com.dudubaika.event.DummyEvent
import com.dudubaika.event.ServiceErrorEvent
import com.dudubaika.event.ServiceUpdateEvent
import com.dudubaika.event.TokenErrorEvent
import com.dudubaika.util.LoadingUtil
import com.dudubaika.util.StatusBarUtil
import com.dudubaika.util.ToastUtil
import com.dudubaika.util.Utils
import com.tendcloud.tenddata.TCAgent
import kotlinx.android.synthetic.main.dialog_service_error.view.*
import kotlinx.android.synthetic.main.dialog_update.view.*
import me.drakeet.materialdialog.MaterialDialog
import me.yokeyword.fragmentation.SupportActivity
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.uiThread
import java.io.File

abstract class SimpleActivity : SupportActivity() {

    protected val mActivity = this
    protected var defaultTitle:String=""
    private var defaultTitleList: ArrayList<String> = arrayListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(getLayout())
        setStatusBar()
        App.instance.addActivity(mActivity)
        EventBus.getDefault().register(mActivity)
        initView()
        initData()
        defaultTitleList.add("启动广告")
        defaultTitleList.add("main")
    }

    override fun onDestroy() {
        super.onDestroy()
        EventBus.getDefault().unregister(mActivity)
        App.instance.removeActivity(mActivity)
    }

    /**
     * 设置状态栏
     */
    protected open fun setStatusBar() {
        StatusBarUtil.immersive(this, resources.getColor(R.color.colorPrimary), 0F)
        StatusBarUtil.darkMode(mActivity, true)
    }

    /**
     * 初始化子类布局
     */
    protected abstract fun getLayout(): Int

    /**
     * 初始化子类View
     */
    protected abstract fun initView()

    /**
     * 初始化子类一些数据
     */
    protected abstract fun initData()

    /**
     * 该方法不执行，只是让Event编译通过
     */
    @Subscribe
    fun dummy(event: DummyEvent) {
    }

    /**
     * 跳转到某个Activity
     */
    protected fun gotoActivity(mContext: Context, toActivityClass: Class<*>, bundle: Bundle?) {
        val intent = Intent(mContext, toActivityClass)
        if (bundle != null) {
            intent.putExtras(bundle)
        }
        mContext.startActivity(intent)
        (mContext as Activity).overridePendingTransition(R.anim.push_right_in, R.anim.not_exit_push_left_out)
    }

    /**
     * 退出Activity
     */
    protected open fun backActivity() {
        finish()
        overridePendingTransition(R.anim.not_exit_push_left_in, R.anim.push_right_out)
    }

    @Subscribe
    fun onTokenErrorEvent(event: TokenErrorEvent) {
        LoadingUtil.hideLoadingDialog(this)
        finish()
    }

    /**
     * 收到了服务器系统升级的消息
     */
    @Subscribe
    fun onServiceUpdateEvent(event: ServiceUpdateEvent) {
        doAsync {
            uiThread {

                val alert = MaterialDialog(mActivity)
                alert.setTitle("版本更新")
                        .setPositiveButton("下载", {
                            val rxPermissions = RxPermissions(mActivity)
                            rxPermissions.request(Manifest.permission.WRITE_EXTERNAL_STORAGE).subscribe { aBoolean ->
                                if (aBoolean) {
                                    showDownLoadDialog(event.downLoadURL)
                                } else {
                                    ToastUtil.showToast(mActivity, "请打开存储权限")
                                }
                            }
                        })
                        .setNegativeButton("忽略", {
                            alert.dismiss()
                            App.instance.exitApp()
                        })
                        .setCanceledOnTouchOutside(false)
                        .show()
            }
        }
    }
    fun showDownLoadDialog(download_url: String) {

        val layoutInflater = LayoutInflater.from(mActivity)
        val contentView = layoutInflater.inflate(R.layout.dialog_update, null)

        val alert = MaterialDialog(mActivity)
        alert.setTitle("正在下载")
        alert.setContentView(contentView)
                .setCanceledOnTouchOutside(false)
                .show()
        val progressBar = contentView.number_progress_bar
        val path = Utils.getDownLoadAppPath()

        FileDownloader.getImpl().create(download_url)
                .setPath(path, true)
                .setForceReDownload(true)
                .setListener(object : FileDownloadListener() {
                    override fun pending(task: BaseDownloadTask, soFarBytes: Int, totalBytes: Int) {
                    }

                    override fun connected(task: BaseDownloadTask?, etag: String?, isContinue: Boolean, soFarBytes: Int, totalBytes: Int) {
                        progressBar.max = totalBytes
                    }

                    override fun progress(task: BaseDownloadTask, soFarBytes: Int, totalBytes: Int) {
                        progressBar.progress = soFarBytes
                    }

                    override fun blockComplete(task: BaseDownloadTask?) {
                    }

                    override fun retry(task: BaseDownloadTask?, ex: Throwable?, retryingTimes: Int, soFarBytes: Int) {
                    }

                    override fun completed(task: BaseDownloadTask) {
                        val targetFilePath = task.targetFilePath
                        installApk(mActivity, targetFilePath)
                    }

                    override fun paused(task: BaseDownloadTask, soFarBytes: Int, totalBytes: Int) {
                    }

                    override fun error(task: BaseDownloadTask, e: Throwable) {
                    }

                    override fun warn(task: BaseDownloadTask) {
                    }
                }).start()
    }

    /**
     * 安装apk
     */
    private fun installApk(activity: Activity, targetFilePath: String) {
        val apkFile = File(targetFilePath)
        if (!apkFile.exists()) {
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
        // 由于没有在Activity环境下启动Activity,设置下面的标签
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        if (Build.VERSION.SDK_INT >= 24) { //判读版本是否在7.0以上
            //参数1 上下文, 参数2 Provider主机地址 和配置文件中保持一致   参数3  共享的文件
            val apkUri = FileProvider.getUriForFile(activity, "com.dudubaika.fileprovider", apkFile)
            //添加这一句表示对目标应用临时授权该Uri所代表的文件
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
        } else {
            intent.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive")
        }
        activity.startActivity(intent)
        Process.killProcess(Process.myPid())
    }

    /**
     * 收到了服务器维护的消息
     */
    @Subscribe
    fun onServiceErrorEvent(event: ServiceErrorEvent) {
        showServiceErrorDialog(event.msg)
    }

    fun showServiceErrorDialog(msg: String) {
        if (isFinishing) {
            return
        }
        val mLayoutInflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val view = mLayoutInflater.inflate(R.layout.dialog_service_error, null, false)
        val mDialog = Dialog(this, R.style.ErrorDialog)
        mDialog.setContentView(view)
        mDialog.setCanceledOnTouchOutside(false)
        mDialog.setCancelable(false)
        view.tv_dialog_error_tips.text = msg
        view.iv_dialog_error_close.setOnClickListener {
            mDialog.dismiss()
            App.instance.exitApp()
        }
        mDialog.show()
    }

    override fun onResume() {
        super.onResume()
        if (!defaultTitleList.contains(defaultTitle)){
            TCAgent.onPageStart(this, this.defaultTitle)
        }
    }

    override fun onPause() {
        super.onPause()
        if (!defaultTitleList.contains(defaultTitle)) {
            TCAgent.onPageEnd(this, this.defaultTitle)
        }
    }
}