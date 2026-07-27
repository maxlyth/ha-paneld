(function(){try{
            if(window.top&&window.top!==window)return;
            if(localStorage.getItem('__hapaneld_panel_defaults'))return;
            localStorage.setItem('dockedSidebar',JSON.stringify('always_hidden'));
            localStorage.setItem('suspendWhenHidden',JSON.stringify(false));
            localStorage.setItem('vibrate',JSON.stringify(false));
            localStorage.setItem('__hapaneld_panel_defaults','1');
        }catch(e){}})();