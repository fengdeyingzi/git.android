/*
 * Copyright 2013 Andrew Romanenco.
 * 
 * This file is part of Gitt.
 * 
 * Gitt is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * Gitt is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with Gitt.  If not, see <http://www.gnu.org/licenses/>. 
 */

package com.romanenco.gitt.git;

import android.util.Log;
import com.romanenco.gitt.GittApp;
import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.DeleteBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.InitCommand;
import org.eclipse.jgit.api.ListTagCommand;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.PullCommand;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.TagCommand;
import org.eclipse.jgit.api.errors.DetachedHeadException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.errors.NotSupportedException;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

/**
 * Common GIT operations based on jgit.
 * 
 * @author Andrew Romanenco
 * 
 */
public class GitHelper {
	
	private static final String TAG = "GitHelper";

	/**
	 * Clone remote HTTP/S repo to local file system.
	 * 克隆
	 * @param url
	 * @param localPath
	 * @param user
	 * @param password
	 * @throws GitError
	 */
	public static void clone(String url, String localPath, String user,
			String password, ProgressMonitor monitor) throws GitError {
		Log.d(TAG, "Cloning: " + url);
		CloneCommand clone = Git.cloneRepository();
		clone.setTimeout(30); // set time out for bad servers
		clone.setURI(url);
		clone.setDirectory(new File(localPath));
		if ((user != null) && (password != null)) {
			UsernamePasswordCredentialsProvider access = new UsernamePasswordCredentialsProvider(
					user, password);
			clone.setCredentialsProvider(access);
		}
		if (monitor != null) {
			clone.setProgressMonitor(monitor);
		}
		
		try {
			FileUtils.deleteDirectory(new File(localPath));
			clone.call();
			return;
		} catch (InvalidRemoteException e) {
			Log.e(TAG, "InvalidRemote", e);
			GittApp.saveErrorTrace(e);
			throw new NotGitRepoError();
		} catch (TransportException e) {
			String trace = GittApp.saveErrorTrace(e);
			if (trace.indexOf("not authorized") != -1) {
				Log.e(TAG, "Auth", e);
				throw new AuthFailError();
			}
			Log.e(TAG, "Transport", e);
			throw new ConnectionError();
		} catch (GitAPIException e) {
			Log.e(TAG, "GitApi", e);
			GittApp.saveErrorTrace(e);
		} catch (IOException e) {
			Log.e(TAG, "IO", e);
			GittApp.saveErrorTrace(e);
		} catch (JGitInternalException e) {
			Log.e(TAG, "GitInternal", e);
			GittApp.saveErrorTrace(e);
			if (e.getCause() instanceof NotSupportedException) {
				throw new ConnectionError();
			} else {
				throw new GitError();
			}
		}
		throw new GitError();
	}
	
	/**
	 * Folder size in bytes.
	 * 获取一个文件夹的大小
	 * @param localPath
	 * @return
	 */
	public static long getRepoSize(String localPath) {
		return FileUtils.sizeOfDirectory(new File(localPath));
	}
	
	
	/**
	 * Current branch/tag name.
	 * 获取当前的分支/标签名
	 * @param path
	 * @return
	 */
	public static String currentBranchName(String localPath) {
		try {
			Git git = Git.open(new File(localPath));
		
			String name = git.getRepository().getBranch();
			
			if (name.length() == 40) {
				//detached head processing
				Map<String, Ref> refs = git.add().getRepository().getAllRefs();
				Iterator<Entry<String, Ref>> iter = refs.entrySet().iterator();
				while (iter.hasNext()) {
					Entry<String, Ref> entry = iter.next();
					if (entry.getValue().getObjectId().getName().equals(name)) {
						if (!entry.getKey().equals("HEAD")) { // so bad, will change later...
							name = entry.getKey();
							break;
						}
					}
				}
			}
			return nameReFormat(name);
		} catch (IOException e) {
			GittApp.saveErrorTrace(e);
		}
		return null;
	}
	
	/**
	 * List all branches and tags in a repo.
	 * 列出所有分支 和标签
	 * @param branches
	 * @param tags
	 * @param path
	 */
	public static void readBranchesAndTags(List<String> refs, String localPath) {
		if (refs == null) return;
		try {
			Git git = Git.open(new File(localPath));
			
			Map<String, Ref> mm = git.getRepository().getAllRefs();
			for (String name: mm.keySet()) {
				if (name.equals("HEAD")||name.startsWith("refs/heads/")) continue;
				refs.add(name);
			}

		} catch (Exception e) {
			GittApp.saveErrorTrace(e);
		}
	}
	
	/**
	 * Format name by taking last part of a ref.
	 * 
	 * @param name
	 * @return
	 */
	private static String nameReFormat(String name) {
		int index = name.lastIndexOf("/");
		if (index > -1) {
			return name.substring(++index);
		} else {
			return name;
		}
	}
	
	/*
	对一个文件夹创建一个空项目
  */
	
	public static void init(String localPath) throws GitError
	{
	try {
	Git git = Git.open(new File(localPath));
	InitCommand co = git.init();
	co.setDirectory(new File(localPath));
	co.call();
	} catch (Exception e) {
	GittApp.saveErrorTrace(e);
	throw new GitError();
	}
	}
	
	/**
	 * Delete local repo.
	 * 删除项目
	 * @param localPath
	 * @throws IOException
	 */
	public static void deleteRepo(String localPath) throws IOException {
		FileUtils.deleteDirectory(new File(localPath));
	} 
	
	/**
	 * Checkout specific branch or tag.
	 * 切换分支
	 * @param localPath
	 * @param name
	 * @throws GitError
	 */
	public static void checkout(String localPath, String name) throws GitError {
		try {
			Git git = Git.open(new File(localPath));
			CheckoutCommand co = git.checkout();
			co.setName(name);
			co.call();
		} catch (Exception e) {
			GittApp.saveErrorTrace(e);
			throw new GitError();
		}
	}
	
	/*
	打tag
	*/
	
	public static void tag(String localPath,String tagName, String message) throws GitError
	{
	try {
	Git git = Git.open(new File(localPath));
	TagCommand co = git.tag();
	co.setName(tagName);
	co.setMessage(message);
	co.call();
	} catch (Exception e) {
	GittApp.saveErrorTrace(e);
	throw new GitError();
	}
	}
	/*
	获取标签列表
	*/
	public static List<org.eclipse.jgit.lib.Ref> tagList(String localPath) throws GitError
	{
	try {
	Git git = Git.open(new File(localPath));
	ListTagCommand co = git.tagList();
	
	return co.call();
	} catch (Exception e) {
	GittApp.saveErrorTrace(e);
	throw new GitError();
	}
	}
	
	/*
	提交代码
	*/
	public static void commit(String localPath,String message) throws GitError
	{
	try {
	Git git = Git.open(new File(localPath));
	CommitCommand co = git.commit();
	co.setMessage(message);
	co.call();
	} catch (Exception e) {
	GittApp.saveErrorTrace(e);
	throw new GitError();
	}
	}
	
	
	/*
	推送
	
	*/
	public static void push(String localPath) throws GitError
	{
	    try {
	    Git git = Git.open(new File(localPath));
	    PushCommand co = git.push();
	    co.setPushAll();
	    co.call();
	    } catch (Exception e) {
	    GittApp.saveErrorTrace(e);
    	throw new GitError();
    	}
	}
	
	/*
	创建分支
	*/
	public static void createBranch(String localPath,String name) throws GitError
	{
	try {
	Git git = Git.open(new File(localPath));
	CreateBranchCommand co = git.branchCreate();
	co.setName(name);
	co.call();
	} catch (Exception e) {
	GittApp.saveErrorTrace(e);
	throw new GitError();
	}
	}
	
	/*
	 删除分支
	 */
	public static void deleteBranch(String localPath,String name) throws GitError
	{
	try {
	Git git = Git.open(new File(localPath));
	DeleteBranchCommand co = git.branchDelete();
	co.setBranchNames(new String[]{name});
	co.call();
	} catch (Exception e) {
	GittApp.saveErrorTrace(e);
	throw new GitError();
	}
	}
	/*
	合并分支
	*/
	public static void mergeBranch(String localPath,String name) throws GitError
	{
	try {
	Git git = Git.open(new File(localPath));
	MergeCommand co = git.merge();
	
	co.call();
	} catch (Exception e) {
	GittApp.saveErrorTrace(e);
	throw new GitError();
	}
	}
	
	
	
	/**
	 * Pull repo.
	 * 拉
	 * @param localPath
	 * @param user
	 * @param password
	 * @param pm
	 * @throws GitError
	 */
	public static void pull(String localPath, String user, String password, ProgressMonitor pm) throws GitError {
		try {
			Git git = Git.open(new File(localPath));
			PullCommand pull = git.pull();
			pull.setProgressMonitor(pm);
			if ((user != null) && (password != null)) {
				UsernamePasswordCredentialsProvider access = new UsernamePasswordCredentialsProvider(
						user, password);
				pull.setCredentialsProvider(access);
			}
			pull.call();
			return;
		} catch (DetachedHeadException e) {
			Log.e(TAG, "Detached head", e);
			GittApp.saveErrorTrace(e);
			throw new NoHeadError();
		} catch (InvalidRemoteException e) {
			Log.e(TAG, "InvalidRemote", e);
			GittApp.saveErrorTrace(e);
			throw new NotGitRepoError();
		} catch (TransportException e) {
			String trace = GittApp.saveErrorTrace(e);
			if (trace.indexOf("not authorized") != -1) {
				Log.e(TAG, "Auth", e);
				throw new AuthFailError();
			}
			Log.e(TAG, "Transport", e);
			throw new ConnectionError();
		} catch (GitAPIException e) {
			Log.e(TAG, "GitApi", e);
			GittApp.saveErrorTrace(e);
		} catch (IOException e) {
			Log.e(TAG, "IO", e);
			GittApp.saveErrorTrace(e);
		} catch (JGitInternalException e) {
			Log.e(TAG, "GitInternal", e);
			GittApp.saveErrorTrace(e);
			if (e.getCause() instanceof NotSupportedException) {
				throw new ConnectionError();
			}
		}
		throw new GitError();
	}
	
	/**
	 * Read log messages, up to maxRecords
	 * 读取log
	 * @param repoPath
	 * @param maxRecords
	 * @return
	 */
	public static List<LogEntry> readRepoHistory(String repoPath, int maxRecords) {
		ArrayList<LogEntry> result = new ArrayList<GitHelper.LogEntry>(maxRecords);
		try {
			Git git = Git.open(new File(repoPath));
			Iterable<RevCommit> logs = git.log().call();
			for (RevCommit commit: logs) {
				result.add(new LogEntry(commit));
				if (--maxRecords == 0) break; 
			}
		} catch (IOException e) {
			Log.e(TAG, "IO", e);
		} catch (NoHeadException e) {
			Log.e(TAG, "NoHead", e);
		} catch (GitAPIException e) {
			Log.e(TAG, "GitApi", e);
		}
		if (maxRecords > 0) {
			result.trimToSize();
		}
		return result;
	}
	
	/**
	 * Log entry.
	 * 
	 * To be updated for more features.
	 * 
	 * @author Andrew Romanenco
	 *
	 */
	public static class LogEntry {
		
		private RevCommit commit;
		
		private LogEntry(RevCommit commit) {
			this.commit = commit;
		}
		
		private static DateFormat format = new SimpleDateFormat("MMM dd, yyyy");
		
		public String getText() {
			StringBuilder sb = new StringBuilder();
			sb.append(commit.getAuthorIdent() != null ? commit.getAuthorIdent().getName(): "");
			sb.append("\n");
			Date date = new Date((long)commit.getCommitTime()*1000);
			sb.append(format.format(date));
			sb.append("\n");
			sb.append(commit.getId().getName());
			sb.append("\n");
			sb.append("\n");
			sb.append(commit.getFullMessage());
			return sb.toString();
		}
	}

}
