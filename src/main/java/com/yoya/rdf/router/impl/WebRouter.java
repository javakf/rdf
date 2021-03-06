/*
 *  Copyright (c) 2016, baihw (javakf@163.com).
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and limitations under the License.
 *
 */

package com.yoya.rdf.router.impl;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.yoya.rdf.Rdf;
import com.yoya.rdf.RdfUtil;
import com.yoya.rdf.log.ILog;
import com.yoya.rdf.log.LogManager;
import com.yoya.rdf.router.AbstractRouter;
import com.yoya.rdf.router.IHandlerProxy;
import com.yoya.rdf.router.IHttpRequest;
import com.yoya.rdf.router.IHttpRequestHandler;
import com.yoya.rdf.router.IHttpResponse;
import com.yoya.rdf.router.IResponse;
import com.yoya.rdf.router.IRouter;
import com.yoya.rdf.router.filter.IFilterChain;
import com.yoya.rdf.router.filter.IRequestFilter;
import com.yoya.rdf.router.filter.impl.SimpleFilterChain;

/**
 * Created by baihw on 16-4-15.
 *
 * 框架内置的一个简单路由实现
 */
public class WebRouter extends AbstractRouter implements IRouter<IHttpRequest, IHttpResponse>{

	/**
	 * 默认的路由逻辑实现扫描包名。
	 */
	public static final String					DEF_WORKBASE	= "rdf.me.handler";

	// 日志处理对象
	private static final ILog					_LOG			= LogManager.getLog( WebRouter.class );

	// 请求处理器参数类型列表。
	private static final Class<?>[]				_PARAMETERTYPES	= new Class<?>[]{ IHttpRequest.class, IHttpResponse.class };

	// 文件类型映射关系
	private static final Map<String, String>	_FILETYPES		= new HashMap<>();

	static{
		_FILETYPES.put( "css", "text/css;charset=UTF-8" );
		_FILETYPES.put( "js", "application/x-javascript;charset=UTF-8" );
		_FILETYPES.put( "map", "application/json;charset=UTF-8" );
		_FILETYPES.put( "json", "application/json;charset=UTF-8" );
		_FILETYPES.put( "html", "text/html" );
		_FILETYPES.put( "xhtml", "text/html" );
		_FILETYPES.put( "htm", "text/html" );
		_FILETYPES.put( "jpg", "image/jpeg" );
		_FILETYPES.put( "ico", "image/x-icon" );
		_FILETYPES.put( "jpeg", "image/jpeg" );
		_FILETYPES.put( "png", "image/png" );
		_FILETYPES.put( "gif", "image/gif" );
		_FILETYPES.put( "svg", "image/svg+xml" );
		_FILETYPES.put( "woff", "application/font-woff" );
	};

	private final String	_IGNORE_URL;

	// web根目录
	private final File		_WEBROOT;

	/**
	 * 构造函数。
	 */
	public WebRouter(){

		String webRoot = Rdf.getWebRoot();
		if( null == webRoot ){ throw new RuntimeException( "web项目必须存在WebRoot目录!" ); }
		// 静态文件相对根目录
		_WEBROOT = new File( webRoot );

		// 获取配置信息对象
		Map<String, String> configMap = new HashMap<>( Rdf.me().getConfigGroup( "web" ) );

		// 初始化请求处理器工作目录。
		String workBase = configMap.get( "workBase" );
		this._HANDLER_IFACE = IHttpRequestHandler.class;
		if( null == workBase || 0 == ( workBase = workBase.trim() ).length() )
			workBase = DEF_WORKBASE;
		configWrokBase( workBase );

		// 获取忽略处理的静态文件路径匹配正则。
		String ignoreUrl = configMap.get( "ignoreUrl" );
		if( null == ignoreUrl || 0 == ( ignoreUrl = ignoreUrl.trim() ).length() ){
			_IGNORE_URL = null;
		}else{
			_IGNORE_URL = ignoreUrl;
		}

		// 初始化过滤器对象。
		// 获取开发人员配置的所有filter名称
		String filterNamesString = configMap.get( "filterNames" );
		if( null == filterNamesString || 0 == ( filterNamesString = filterNamesString.trim() ).length() )
			return;
		String[] filterNames = filterNamesString.split( "," );
		for( String filterName : filterNames ){
			if( null == filterName || 0 == ( filterName = filterName.trim() ).length() )
				continue;

		}
		// 查找每个filter对应的配置信息，初始化filter并加入到路由列表中
		for( String filterName : filterNames ){
			Map<String, String> filterConfigMap = RdfUtil.removeSubMap( configMap, filterName + "." );
			addMappingFilter( filterName, filterConfigMap );
		}

	}

	public void route( IHttpRequest request, IHttpResponse response ){
		String reqPath = request.getPath();
		if( null == reqPath || 0 == ( reqPath = reqPath.trim() ).length() || "/".equals( reqPath ) ){
			// 没有指定具体请求路径时默认为请求首页欢迎页面。
			reqPath = "/index.html";
		}
		if( reqPath.startsWith( "/WEB-INF/" ) ){
			// 不允许访问WEB-INF目录下的任何文件。
			response.setError( IHttpResponse.CODE_FORBIDDEN_ACCESS, "not allowed to access!" );
			return;
		}
		try{

			// 共享当前线程请求对象。
			Router.setRequest( request );

			// 先执行请求路径上的匹配过滤器对请求进行过滤。
			IRequestFilter[] matchFilters = matchFilters( reqPath );
			if( null != matchFilters && matchFilters.length > 0 ){
				IFilterChain afChain = new SimpleFilterChain( matchFilters );
				boolean isEnd = afChain.doFilter( request, response );
				if( isEnd ){
					// 如果过滤器中响应了数据，则直接返回，不再进行后续处理。
					return;
				}
			}

			// 需要忽略的静态文件处理。
			if( null != _IGNORE_URL && reqPath.matches( _IGNORE_URL ) ){
				File staticFile = new File( _WEBROOT, reqPath );
				if( staticFile.exists() && staticFile.isFile() ){
					// 根据文件后缀名设置指定的MIME类型。
					String contentType = null;
					int ndx = reqPath.lastIndexOf( '.' );
					if( -1 != ndx ){
						String extName = reqPath.substring( ndx + 1 );
						if( _FILETYPES.containsKey( extName ) )
							contentType = _FILETYPES.get( extName );
					}
					response.setDataInputStream( contentType, new FileInputStream( staticFile ) );
				}else{
					response.setError( IHttpResponse.CODE_NOT_FOUND, "not found:".concat( reqPath ) );
				}
				return;
			}

			// 解析url路径获取请求路由元素。
			UrlUtil.UrlItem urlItem = UrlUtil.parseUrl( reqPath, new Function<String, Boolean>(){
				@Override
				public Boolean apply( String actionName ){
					return _HANDLERS.containsKey( actionName );
				}
			}, new BiFunction<String, String, Boolean>(){
				@Override
				public Boolean apply( String actionName, String startName ){
					// 保留的方法关键字。
					if( SimpleHandlerProxy.KEY_METHODNAMES.equals( startName ) )
						return true;
					IHandlerProxy handlerProxy = _HANDLERS.get( actionName );
					return null != handlerProxy && handlerProxy.hasMethod( startName );
				}
			} );

			String action = urlItem.getAction();
			if( !_HANDLERS.containsKey( action ) ){
				( ( IHttpResponse )response ).setDataByJsonCMD( IResponse.CODE_NOT_FOUND, String.format( "not found action: %s", action ) );
				return;
			}

			// 执行业务处理逻辑
			IHandlerProxy handlerProxy = _HANDLERS.get( action );
//          // TODO: url参数设置进请求对象。
//		    urlItem.getParams() ;
			handlerProxy.doMethod( urlItem.getStart(), request, response );

		}catch( Exception e ){
			StringWriter errorSW = new StringWriter();
			e.printStackTrace( new PrintWriter( errorSW ) );
			String errorMsg = errorSW.toString();
			_LOG.error( errorMsg );
			( ( IHttpResponse )response ).setDataByJsonCMD( IResponse.CODE_INTERNAL_ERROR, e.getMessage(), errorMsg );
		}finally{
			// 移除当前线程共享请求对象。
			Router.removeRequest();
		}
	}

	@Override
	protected IHandlerProxy createProxy( Class<?> clazz ){
		return new SimpleHandlerProxy( clazz, _PARAMETERTYPES );
	}

}
