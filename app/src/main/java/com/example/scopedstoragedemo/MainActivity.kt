package com.example.scopedstoragedemo

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.ContentValues.TAG
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.scopedstoragedemo.databinding.ActivityMainBinding
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

const val PICK_FILE = 1
const val PICK_IMAGES = 2
const val CREATE_WRITE_REQUEST = 3
const val ALL_FILES_ACCESS_PERMISSION = 4

/**
 * 1. 什么是作用域存储呢？
 *  从Android 10开始，每个应用程序只能有权在自己的外置存储空间关联目录下读取和创建文件，获取该关联目录的代码是：context.getExternalFilesDir()
 *  大致路径为: /storage/emulated/0/Android/data/<包名>/files
 *  这个目录中的文件会被计入到应用程序的占用空间当中，同时也会随着应用程序的卸载而被删除
 * 2. 需要访问其他目录该怎么办呢？
 *  Android系统针对文件类型进行了分类，图片、音频、视频这三类文件将可以通过MediaStore API来进行访问，而其他类型的文件则需要使用系统的文件选择器来进行访问。
 *  我们的应用程序向媒体库贡献的图片、音频或视频，将会自动拥有其读写权限，不需要额外申请READ_EXTERNAL_STORAGE和WRITE_EXTERNAL_STORAGE权限。
 *  而如果你要读取其他应用程序向媒体库贡献的图片、音频或视频，则必须要申请READ_EXTERNAL_STORAGE权限才行。WRITE_EXTERNAL_STORAGE权限将会在未来的Android版本中废弃。
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val permissionsToRequire = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequire.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequire.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (permissionsToRequire.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequire.toTypedArray(), 0)
        }
        binding.browseAlbum.setOnClickListener {
            val intent = Intent(this, BrowseAlbumActivity::class.java)
            startActivity(intent)
        }
        binding.addImageToAlbum.setOnClickListener {
            val bitmap = BitmapFactory.decodeResource(resources, R.drawable.image)
            val displayName = "${System.currentTimeMillis()}.jpg"
            val mimeType = "image/jpeg"
            val compressFormat = Bitmap.CompressFormat.JPEG
            addBitmapToAlbum(bitmap, displayName, mimeType, compressFormat)
        }
        binding.downloadFile.setOnClickListener {
            val fileUrl = "http://guolin.tech/android.txt"
            val fileName = "android.txt"
            downloadFile(fileUrl, fileName)
        }
        binding.pickFile.setOnClickListener {
            pickFileAndCopyUriToExternalFilesDir()
        }
        binding.writeRequest.setOnClickListener {
            val intent = Intent(this, BrowseAlbumActivity::class.java)
            intent.putExtra("pick_files", true)
            startActivityForResult(intent, PICK_IMAGES)
        }
        binding.manageExternalStorage.setOnClickListener {
            requestAllFilesAccessPermission()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 0) {
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "You must allow all the permissions.", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun addBitmapToAlbum(bitmap: Bitmap, displayName: String, mimeType: String, compressFormat: Bitmap.CompressFormat) {
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        /**
         * 接下来是图片存储路径:
         * Android 10中新增了一个RELATIVE_PATH常量，表示文件存储的相对路径，可选值有DIRECTORY_DCIM、DIRECTORY_PICTURES、DIRECTORY_MOVIES、DIRECTORY_MUSIC等，分别表示相册、图片、电影、音乐等目录。
         * 而在之前的系统版本中并没有RELATIVE_PATH，所以我们要使用DATA常量（已在Android 10中废弃），并拼装出一个文件存储的绝对路径才行。
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 图片路径: /storage/emulated/0/DCIM/1694076059057.jpg
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM)
        } else {
            values.put(MediaStore.MediaColumns.DATA, "${Environment.getExternalStorageDirectory().path}/${Environment.DIRECTORY_DCIM}/$displayName")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            val outputStream = contentResolver.openOutputStream(uri)
            if (outputStream != null) {
                bitmap.compress(compressFormat, 100, outputStream)
                outputStream.close()
                Toast.makeText(this, "Add bitmap to album succeeded.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun downloadFile(fileUrl: String, fileName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "You must use device running Android 10 or higher", Toast.LENGTH_SHORT).show()
            return
        }
        thread {
            try {
                val url = URL(fileUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                val inputStream = connection.inputStream
                val bis = BufferedInputStream(inputStream)
                val values = ContentValues()
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                // android 10的文件存储相对路径
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                // 根据你把信息配置到values中,向contentResolver申请出一个写入的uri(如下是外置卡的存储路径),往这个uri里面写就行了
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                // fixme: INTERNAL_CONTENT_URI: error, Writing to internal storage is not supported.
                //        val uri = contentResolver.insert(MediaStore.Downloads.INTERNAL_CONTENT_URI, values)
                Log.i(TAG, "downloadFile: $uri") // /storage/emulated/0/Download/android.txt
                if (uri != null) {
                    val outputStream = contentResolver.openOutputStream(uri)
                    if (outputStream != null) {
                        val bos = BufferedOutputStream(outputStream)
                        val buffer = ByteArray(1024)
                        var bytes = bis.read(buffer)
                        while (bytes >= 0) {
                            bos.write(buffer, 0 , bytes)
                            bos.flush()
                            bytes = bis.read(buffer)
                        }
                        bos.close()
                        runOnUiThread {
                            Toast.makeText(this, "$fileName is in Download directory now.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                bis.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 如果我们要读取SD卡上非图片、音频、视频类的文件，比如说打开一个PDF文件，这个时候就不能再使用MediaStore API了，而是要使用文件选择器。
     * 并且必须要使用手机系统中内置的文件选择器
     */
    private fun pickFileAndCopyUriToExternalFilesDir() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        startActivityForResult(intent, PICK_FILE)
    }

    private fun requestAllFilesAccessPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) {
            Toast.makeText(this, "We can access all files on external storage now", Toast.LENGTH_SHORT).show()
        } else {
            val builder = AlertDialog.Builder(this)
                .setTitle("Tip")
                .setMessage("We need permission to access all files on external storage")
                .setPositiveButton("OK") { _, _ ->
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivityForResult(intent, ALL_FILES_ACCESS_PERMISSION)
                }
                .setNegativeButton("Cancel", null)
            builder.show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            PICK_FILE -> {
                // 获取到用户选中文件的Uri，之后通过ContentResolver打开文件输入流来进行读取就可以了
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val uri = data.data
                    if (uri != null) {
                        val fileName = getFileNameByUri(uri)
                        // 兼容第三方SDK没有适配作用域存储,就是把uri对应的文件copy到我们应用的外置存储关联目录就ok,在这个目录下之前咋操作外置卡的现在还是不变
                        copyUriToExternalFilesDir(uri, fileName)
                    }
                }
            }
            PICK_IMAGES -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val urisToModify = data.getSerializableExtra("checked_uris") as ArrayList<Uri>
                        val editPendingIntent = MediaStore.createWriteRequest(contentResolver, urisToModify)
                        startIntentSenderForResult(editPendingIntent.intentSender, CREATE_WRITE_REQUEST,
                            null, 0, 0, 0)
                    } else {
                        Toast.makeText(this, "Write permissions are granted", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            CREATE_WRITE_REQUEST -> {
                if (resultCode == Activity.RESULT_OK) {
                    Toast.makeText(this, "Write permissions are granted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Write permissions are denied", Toast.LENGTH_SHORT).show()
                }
            }
            ALL_FILES_ACCESS_PERMISSION -> {
                requestAllFilesAccessPermission()
            }
        }
    }

    private fun getFileNameByUri(uri: Uri): String {
        var fileName = System.currentTimeMillis().toString()
        val cursor = contentResolver.query(uri, null, null, null, null)
        if (cursor != null && cursor.count > 0) {
            cursor.moveToFirst()
            fileName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
            cursor.close()
        }
        return fileName
    }

    private fun  copyUriToExternalFilesDir(uri: Uri, fileName: String) {
        thread {
            val inputStream = contentResolver.openInputStream(uri)
            val tempDir = getExternalFilesDir("temp")
            if (inputStream != null && tempDir != null) {
                /**
                 * 从Android 10开始，每个应用程序只能有权在自己的外置存储空间关联目录下读取和创建文件，获取该关联目录的代码是：context.getExternalFilesDir()
                 *      /storage/emulated/0/Android/data/<包名>/files
                 * 这个例子是把uri对应的文件copy到自己应用的外置存储空间:
                 * file路径为: /storage/emulated/0/Android/data/com.example.scopedstoragedemo/files/temp/IMG_20230906_211722.jpg
                 */
                val file = File("$tempDir/$fileName")
                val fos = FileOutputStream(file)
                val bis = BufferedInputStream(inputStream)
                val bos = BufferedOutputStream(fos)
                val byteArray = ByteArray(1024)
                var bytes = bis.read(byteArray)
                while (bytes > 0) {
                    bos.write(byteArray, 0, bytes)
                    bos.flush()
                    bytes = bis.read(byteArray)
                }
                bos.close()
                fos.close()
                runOnUiThread {
                    Toast.makeText(this, "Copy file into $tempDir succeeded.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * 网络图片添加到相册,道理都一样,都是从contentResolver那获取一个uri,然后往里面写入流就行
     * 注意: 这个uri如果是EXTERNAL_CONTENT_URI就是外部类型(/storage/emulated/0/)
     *      INTERNAL_CONTENT_URI这个类型慎用, Error: Writing to internal storage is not supported
     */
    fun writeInputStreamToAlbum(inputStream: InputStream, displayName: String, mimeType: String) {
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM)
        } else {
            values.put(MediaStore.MediaColumns.DATA, "${Environment.getExternalStorageDirectory().path}/${Environment.DIRECTORY_DCIM}/$displayName")
        }
        val bis = BufferedInputStream(inputStream)
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            val outputStream = contentResolver.openOutputStream(uri)
            if (outputStream != null) {
                val bos = BufferedOutputStream(outputStream)
                val buffer = ByteArray(1024)
                var bytes = bis.read(buffer)
                while (bytes >= 0) {
                    bos.write(buffer, 0 , bytes)
                    bos.flush()
                    bytes = bis.read(buffer)
                }
                bos.close()
            }
        }
        bis.close()
    }

}
