#!/bin/sh

if [ $# -lt 1 ];
then
	echo "Specify the script"
	exit 1
else
	
	case "$1" in
		MVTO*) 	prefix="MVTO" ;;
		2PL*) 	prefix="2PL" ;;
		MVCC2PL*) prefix="MVCC2PL";;	
		*)	prefix="MVTO 2PL MVCC2PL";;
	esac

	if [ $# -eq 2 ];
	then
		scp "$2"@icdataportal2:~/transactional-key-value-store-project/benchmarks/results/* ../results
	fi

	parsedFile="parsed.bm"

	for p in $prefix
	do
		benchmarkResults=../results/"$p"_results.csv

		if [ -f "$benchmarkResults" ];
		then
			cat "$benchmarkResults" | grep -i "#BM-" | sed -e 's/#BM- //' > $parsedFile
			echo $parsedFile | gnuplot "$p"_script.gp
		else
			echo "Could not find $benchmarkResults"	
		fi
	done

    gnuplot MERGE_script.gp
fi