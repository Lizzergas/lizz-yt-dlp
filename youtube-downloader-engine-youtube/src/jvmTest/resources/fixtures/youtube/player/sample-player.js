var XY={rv:function(a){a.reverse()},sw:function(a,b){var c=a[0];a[0]=a[b%a.length];a[b]=c},sp:function(a,b){a.splice(0,b)}};
sig=function(a){a=a.split("");XY.sw(a,2);XY.rv(a);XY.sp(a,1);return a.join("")};
nf=function(a){a=a.split("");XY.rv(a);return a.join("")};
b=0;f.get("n")&&(b=nf(b));
