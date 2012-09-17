package com.sketchpunk.ocomicreader.lib;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Stack;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.DatabaseUtils.InsertHelper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;

import sage.data.Sqlite;

public class ComicLibrary{
	private static Thread mWorkerThread = null;
	
	/*========================================================
	status constants*/
	public final static int STATUS_NOSETTINGS = 1;
	public final static int STATUS_COMPLETE = 0;

	
	/*========================================================
	Thread safe messaging*/
	//Object used to handle threadsafe call backs
	public static Handler EventHandler = new Handler(){ public void handleMessage(Message msg){ ComicLibrary.onHandle(msg); }};
	
	//Execute the requested Call back.
    public static void onHandle(Message msg){
    	SyncCallback cb = (SyncCallback)msg.obj;
    	Bundle data = msg.getData();
    	
    	switch(msg.what){
    		//...............................
    		case 1: //progress
    			if(cb != null) cb.OnSyncProgress(data.getString("msg"));
    		break;
    			
       		//...............................
    		case 0: //complete
    			if(cb != null) cb.onSyncComplete(data.getInt("status"));
    			mWorkerThread = null;
    		break;
    	}//switch
    }//func
	    
   
	/*========================================================
	sync methods*/
    public static boolean startSync(Context context){
		//...............................
    	if(mWorkerThread != null){
    		if(mWorkerThread.isAlive()) return false;
    	}//func
    	
    	//...............................
		mWorkerThread = new Thread(new SyncRunnable(context));
		mWorkerThread.start();
		return true;
    }//func
    
	public static boolean clearAll(Context context){
		//................................................
		//Delete thumbnails
		String cachePath = Environment.getExternalStorageDirectory().getAbsolutePath() + "/OpenComicReader/thumbs";

		File fObj = new File(cachePath);
		File[] fList = fObj.listFiles(new ThumbFindFilter());
		if(fList == null) return false;

		for(File file:fList) file.delete();

		//................................................
		Sqlite.delete(context,"ComicLibrary","",null);
		return true;
	}//func
	
	
	//************************************************************
	//Import comics into Library
	//************************************************************
	protected static class SyncRunnable implements Runnable{
		private Context mContext;
		private Sqlite mDb;
		private String mCachePath;

		public SyncRunnable(Context context){
			mContext = context;
			
			//........................................
			//Make sure the cache folder exists.
			mCachePath = Environment.getExternalStorageDirectory().getAbsolutePath() + "/OpenComicReader/thumbs/";
			File file = new File(mCachePath);
	        if(!file.exists()) file.mkdirs();

			//........................................
	        //Create nomedia file so thumbs aren't indexed for gallery
	        file = new File(mCachePath,".nomedia");
	        if (!file.exists()){
	            try{ file.createNewFile(); }catch (Exception e){}
	        }//if
		}//func

		@Override
		public void run(){
			int status = ComicLibrary.STATUS_COMPLETE;
			mDb = new Sqlite(mContext); mDb.openWrite();
			//.....................................
			try{
				if((status = crawlComics()) == 0) createCovers();
			}catch(Exception e){
				System.out.println("Sync " + e.getMessage());
			}//try

			//.....................................
			//Complete
			mDb.close();
			sendComplete(status);			
			mContext = null;
		}//func
		
		/*========================================================
		Send Thread safe message*/
		private void sendProgress(String txt){
			Bundle rtn = new Bundle();
			rtn.putString("msg",txt);
			
			Message msg = new Message();
			msg.what = 1;
			msg.obj = mContext;
			msg.setData(rtn);
			
			ComicLibrary.EventHandler.sendMessage(msg);
		}//func
		
		private void sendComplete(int status){
			Bundle rtn = new Bundle();
			rtn.putInt("status",status);
			
			Message msg = new Message();
			msg.what = 0;
			msg.obj = mContext;
			msg.setData(rtn);
			
			ComicLibrary.EventHandler.sendMessageDelayed(msg,200);
		}//func

		
		/*========================================================
		Syncing Process */
		
		//Crawls the Two paths in settings for comic archive files.
		private int crawlComics(){
			SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
			ComicFindFilter filter = new ComicFindFilter();
	    	Stack<String> stack = new Stack<String>();
	    	File[] fList;
	    	File fObj;
	    	String tmp,path;

	    	//............................................
	    	//Set initial paths
	    	tmp = prefs.getString("syncfolder1","");
	    	if(!tmp.isEmpty()) stack.push(tmp);
	    	
	    	tmp = prefs.getString("syncfolder2","");
	    	if(!tmp.isEmpty()) stack.push(tmp);
	    	
	    	if(stack.size() == 0) return ComicLibrary.STATUS_NOSETTINGS;
			
	    	//............................................
	    	//setup db stuff.
	    	InsertHelper dbInsert = mDb.getInsertHelper("ComicLibrary");
	    	
			int iComicID = dbInsert.getColumnIndex("comicID");
			int iTitle = dbInsert.getColumnIndex("title");
			int iPath = dbInsert.getColumnIndex("path");
			int iPgCount = dbInsert.getColumnIndex("pgCount");
			int iPgRead = dbInsert.getColumnIndex("pgRead");
			int iPgCurrent = dbInsert.getColumnIndex("pgCurrent");
			int iIsCoverExists = dbInsert.getColumnIndex("isCoverExists");
			
			mDb.beginTransaction();

			//............................................
	    	while(!stack.isEmpty()){
	    		//get list and do some validation
	    		fObj = new File(stack.pop());
	    		if(!fObj.exists()) continue;
	    		fList = fObj.listFiles(filter); if(fList == null) continue;

	    		//files/dir found.
	    		for(File file:fList){
	    			if(file.isDirectory()){
	    				stack.push(file.getPath()); //add to stack to continue to crawl.
	    			}else{
	    				//------------------------------
	    				//Check if already in library
	    				sendProgress(file.getName());
	    				path = file.getPath();

	    				tmp = mDb.scalar("SELECT comicID FROM ComicLibrary WHERE path = '"+path.replace("'","''")+"'",null);
	    				if(!tmp.isEmpty()) continue;

	    				//------------------------------
	    				//Not found, add it to library.
	    				dbInsert.prepareForInsert();
						dbInsert.bind(iComicID,UUID.randomUUID().toString());
	    				dbInsert.bind(iTitle,sage.io.Path.RemoveExt(file.getName()));
	    				dbInsert.bind(iPath,path);
	    				dbInsert.bind(iPgCount,0);
	    				dbInsert.bind(iPgRead,0);
	    				dbInsert.bind(iPgCurrent,0);
	    				dbInsert.bind(iIsCoverExists,0);
	    				
						if(dbInsert.execute() == -1){System.out.println("ERROR");}//if
	    			}//if
	    		}//for
	    	}//while
	    	
	    	//............................................
	    	mDb.commit();
			mDb.endTransaction();
			
	    	return ComicLibrary.STATUS_COMPLETE;
		}//func

		//look at what library items don't have covers yet and process it.
		private void createCovers(){
			int[] outVar = {0,0}; //PageCount,IsCoverCreated
			Cursor cur = mDb.raw("SELECT comicID,path FROM ComicLibrary WHERE isCoverExists = 0",null);
			
			for(boolean isOk = cur.moveToFirst(); isOk; isOk = cur.moveToNext()){
				sendProgress("Cover for " + cur.getString(1));
				processArchive(cur.getString(0),cur.getString(1),outVar);

				if(outVar[0] > 0){//if pagecnt is at least 1, update library.
					mDb.execSql(String.format("UPDATE ComicLibrary SET pgCount=%d,isCoverExists=%d WHERE comicID = '%s'",outVar[0],outVar[1],cur.getString(0)),null);
				}//if
			}//for
			
			cur.close(); cur = null;
		}//func
		
		//look through archive for page count and the first image to use as a cover.
		private void processArchive(String fileID,String path,int[] outVar){
			int cntPage = 0;
			try{
				String filePath;
				String coverEntry = "";
				
				ZipEntry zItem;
				ZipFile oZip = new ZipFile(path);
				Enumeration zEnum = oZip.entries();

				//..................................
				//loop through getting page count and first page to make cover out of.
				while(zEnum.hasMoreElements()) {
					zItem = (ZipEntry)zEnum.nextElement();
					if(zItem.isDirectory()) continue;
					
					filePath = zItem.getName().toLowerCase();
					if(filePath.endsWith(".jpg") || filePath.endsWith(".gif") || filePath.endsWith(".png")){
						if(cntPage == 0) coverEntry = zItem.getName();
						cntPage++;
					}//if
				}//while

				//..................................
				//if a page is found, make a thumb out of it.
				if(!coverEntry.equals("")){
					outVar[1] = (createThumb(oZip,coverEntry,fileID))?1:0;
				}else outVar[1] = 0;

				//..................................
				oZip.close(); oZip = null;
			}catch(Exception e){
				System.err.println("ProcessComic " + e.getMessage());
			}//try
			
			outVar[0] = cntPage;
		}//func
	
		//stream page out of archive and resize to use as a thumb
		private boolean createThumb(ZipFile oZip,String coverPath,String fileID){
			boolean rtn = false;
			ZipEntry zItem = oZip.getEntry(coverPath);
					
			if(zItem != null){
				InputStream iStream = null;
				Bitmap bmp = null;
				
				try{
					iStream = oZip.getInputStream(zItem);				
					
					//....................................
					//Get file dimension
					BitmapFactory.Options bmpOption = new BitmapFactory.Options();
					bmpOption.inJustDecodeBounds = true;
					BitmapFactory.decodeStream(iStream,null,bmpOption);
					
					//calc scale
					int scale = (bmpOption.outHeight > 200)? Math.round((float)bmpOption.outHeight/200) : 0;
					bmpOption.inSampleSize = scale;
					bmpOption.inJustDecodeBounds = false;
					
					//....................................
					//Load bitmap and rescale
					iStream.close(); //the first read should of closed the stream. Just do it just incase it didn't
					iStream = oZip.getInputStream(zItem);	
					bmp = BitmapFactory.decodeStream(iStream,null,bmpOption);
					
					//....................................
					//Save bitmap to file
					File file = new File(mCachePath + fileID + ".jpg");
					FileOutputStream out = new FileOutputStream(file);
					bmp.compress(Bitmap.CompressFormat.JPEG,70,out);

					//....................................
					out.close();
					bmp.recycle(); bmp = null;
					
					rtn = true;
				}catch(Exception e){
					System.err.println("Error creating thumb " + e.getMessage());
					if(bmp != null){ bmp.recycle(); bmp = null; }//if
				}//try
				
				if(iStream != null){
					try{ iStream.close(); }catch(Exception e){}
				}//if
			}//if
			
			return rtn;
		}//func
	}//cls

	
	//************************************************************
	// Support Objects
	//************************************************************
	public static interface SyncCallback{
		public void OnSyncProgress(String txt);
		public void onSyncComplete(int status);
	}//interface
	
    protected static class ComicFindFilter implements java.io.FileFilter{
    	private final String[] mExtList = new String[]{".zip",".cbz"};
    	public boolean accept(File o){
    		if(o.isDirectory()) return true; //Want to allow folders
    		for(String extension:mExtList){
    			if(o.getName().toLowerCase().endsWith(extension)) return true;
    		}//for
    		return false;
    	}//func
    }//cls
	
    protected static class ThumbFindFilter implements java.io.FileFilter{
    	public boolean accept(File o){
    		if(o.isDirectory()) return false;
    		else if(o.getName().toLowerCase().endsWith(".jpg")) return true;
    		return false;
    	}//func
    }//cls
}//cls